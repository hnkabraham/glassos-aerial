package io.github.abhishekkaisar.glassos

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings backups as plain JSON files in shared storage, so they can be copied to another TV with adb or a file
 * manager and restored there without a system document picker (which Fire TV does not have). Written to
 * Documents/GlassOS-Aerial when GlassOS may write shared storage, else to the app's own external folder (which is
 * deleted with the app; the UI says so). See [BackupCodec] for what a backup holds.
 */
object Backup {
    const val FOLDER = "GlassOS-Aerial"
    private const val PREFIX = "glassos-aerial-backup"

    @Suppress("DEPRECATION")
    fun sharedDir(): File = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), FOLDER)

    /** Android/data/<package>/files/backups: needs no permission, but goes when GlassOS is uninstalled. */
    fun appDir(ctx: Context): File? = try { ctx.getExternalFilesDir("backups") } catch (e: Throwable) { null }

    fun canWriteShared(ctx: Context) = Build.VERSION.SDK_INT < 23 ||
        ctx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    class Saved(val file: File, val shared: Boolean)

    /**
     * Writes [text] to a new, timestamped file: to a temporary file first, synced, then renamed. Off the UI thread.
     * A backup over [BackupCodec.MAX_BYTES] (which restoring would refuse) is refused before any file is created.
     */
    fun write(ctx: Context, text: String, now: Long = System.currentTimeMillis()): Saved {
        val bytes = BackupCodec.fileBytes(text)
        val name = "$PREFIX-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now)) + ".json"
        if (canWriteShared(ctx)) {
            try { return Saved(writeAtomic(sharedDir(), name, bytes), true) }
            catch (e: IOException) { WLog.i("backup: shared storage failed: $e") }
            catch (e: SecurityException) { WLog.i("backup: shared storage refused: $e") }
        }
        val dir = appDir(ctx) ?: throw IOException("No storage is available for a backup.")
        return Saved(writeAtomic(dir, name, bytes), false)
    }

    fun writeAtomic(dir: File, name: String, bytes: ByteArray): File {
        if (bytes.size > BackupCodec.MAX_BYTES) throw BackupCodec.TooLarge(bytes.size)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create ${dir.path}")
        val target = File(dir, name)
        val tmp = File(dir, ".$name.tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(target)) throw IOException("Cannot rename the backup into place in ${dir.path}")
        } finally {
            if (tmp.exists()) tmp.delete()
        }
        return target
    }

    /** Backups in both folders, newest first. Any .json file there is listed (a backup copied in under another name). */
    fun list(ctx: Context): List<File> {
        val dirs = listOfNotNull(sharedDir(), appDir(ctx))
        return dirs.flatMap { d ->
            try { d.listFiles()?.filter { it.isFile && it.name.endsWith(".json", true) && !it.name.startsWith(".") } ?: emptyList() }
            catch (e: Exception) { emptyList() }
        }.distinctBy { it.absolutePath }.map { it to (try { it.lastModified() } catch (e: Exception) { 0L }) }
            .sortedByDescending { it.second }.map { it.first }
    }

    /**
     * Reads a backup: never more than [BackupCodec.MAX_BYTES] + 1 bytes (a file that grows while being read is still
     * caught), and any failure (permission, a missing file, an I/O error) becomes a message. Off the UI thread.
     */
    fun read(f: File): BackupResult = try {
        val len = f.length()
        if (len > BackupCodec.MAX_BYTES) BackupResult.Error("${f.name} is too large for a backup (${(len + 1023) / 1024} KB; the limit is ${BackupCodec.MAX_BYTES / 1024} KB).")
        else {
            val buf = java.io.ByteArrayOutputStream()
            f.inputStream().use { input ->
                val chunk = ByteArray(16 * 1024)
                while (buf.size() <= BackupCodec.MAX_BYTES) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    buf.write(chunk, 0, n)
                }
            }
            if (buf.size() > BackupCodec.MAX_BYTES) BackupResult.Error("${f.name} is too large for a backup (the limit is ${BackupCodec.MAX_BYTES / 1024} KB).")
            else BackupCodec.decode(String(buf.toByteArray(), Charsets.UTF_8))
        }
    } catch (e: Exception) {
        BackupResult.Error("${f.name} could not be read (${e.javaClass.simpleName}). Check that the file is still there and that GlassOS has storage access (Settings > Permissions).")
    }

    /** A path as the user knows it: /sdcard/... rather than /storage/emulated/0/... */
    @Suppress("DEPRECATION")
    fun display(f: File): String {
        val root = Environment.getExternalStorageDirectory().path
        return if (f.path.startsWith(root)) "/sdcard" + f.path.removePrefix(root) else f.path
    }
}
