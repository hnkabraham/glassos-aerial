plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// Screen mirroring receiver (AirPlay protocol), built from the vendored native sources in src/main/cpp.
// -Pglassos.airplay.native=false builds it without natives: no NDK, CMake or OpenSSL download is needed, the APK
// has no lib/ directory and the feature stays hidden. ndkVersion, prefab and the Oboe dependency are gated too,
// because AGP's prefab step reads the NDK.
val withNative = providers.gradleProperty("glassos.airplay.native").orNull != "false"

android {
    namespace = "io.github.abhishekkaisar.glassos.airplay"
    compileSdk = 34
    buildToolsVersion = "34.0.0"
    if (withNative) ndkVersion = "27.0.12077973"

    defaultConfig {
        minSdk = 22
        consumerProguardFiles("consumer-rules.pro")
        if (withNative) {
            // armeabi-v7a for Fire TV (32-bit userspace), arm64-v8a for 64-bit TVs and the Apple Silicon emulator.
            ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
            externalNativeBuild {
                cmake {
                    arguments += listOf(
                        "-DANDROID_STL=c++_shared",
                        "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                        // Debug APKs also run on the TV: no -O0 crypto or audio code.
                        "-DCMAKE_BUILD_TYPE=RelWithDebInfo",
                        "-DAIRPLAY_FFMPEG=OFF",
                        // The OpenSSL tarball (hash-pinned in CMakeLists.txt) is kept here between clean builds.
                        "-DAIRPLAY_DOWNLOAD_DIR=${rootDir}/.cache/airplay-downloads",
                    )
                    targets += "airplay_native"
                }
            }
        }
    }

    if (withNative) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }

    buildFeatures {
        prefab = withNative
        buildConfig = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint {
        // Mirrors :app, so ported code cannot break CI; the report is in airplay/build/reports/.
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // Low-latency audio output for the native receiver. A prefab-only AAR: native code, no classes, no AndroidX.
    if (withNative) implementation("com.google.oboe:oboe:1.9.3")
    testImplementation("junit:junit:4.13.2")
}
