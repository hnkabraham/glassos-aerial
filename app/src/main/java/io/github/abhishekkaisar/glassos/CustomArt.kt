package io.github.abhishekkaisar.glassos

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import java.io.File

object CustomArt {
    /** Manager writes plain PNG files atomically; invalid or oversized files fall back to the app's own art. */
    fun load(pkg: String): Bitmap? = try {
        if (!Folders.isValidPackage(pkg)) null else {
            val root = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "GlassOS/icons")
            val file = File(root, "$pkg.png")
            if (file.canonicalFile.parentFile != root.canonicalFile || !file.isFile || file.length() !in 1..(2 * 1024 * 1024)) null else {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, options)
                if (options.outWidth !in 1..2048 || options.outHeight !in 1..2048) null else {
                    options.inJustDecodeBounds = false
                    options.inSampleSize = 1
                    while (options.outWidth / options.inSampleSize > 800 || options.outHeight / options.inSampleSize > 450) options.inSampleSize *= 2
                    BitmapFactory.decodeFile(file.path, options)
                }
            }
        }
    } catch (_: Exception) { null }
}
