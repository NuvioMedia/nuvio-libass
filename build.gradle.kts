// nuvio-libass - atlas-based libass subtitle rendering for Android/ExoPlayer.
// Based on Plezy by edde746 (https://github.com/edde746/plezy),
// which is a fork of libass-android by peerless2012
// (https://github.com/peerless2012/libass-android).
// See ATTRIBUTION.md for full credits.

plugins {
    id("com.android.library") version "8.7.3"
    id("org.jetbrains.kotlin.android") version "2.1.10"
}

android {
    namespace = "com.nuvio.tv.libass"
    compileSdk = 35
    // NDK r29 for 16 KB page alignment (Android 15+) and libc++ compat with mpv-android-lib.
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DLIBASS_CACHE_DIR=${layout.buildDirectory.get().asFile}/libass-prebuilt"
                )
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Don't bundle libc++_shared.so in the AAR - the app already gets it from
    // its other native dependencies (mpv-android-lib, ffmpeg). Bundling a stale
    // copy here causes symbol conflicts via pickFirst.
    packaging {
        jniLibs {
            excludes += "**/libc++_shared.so"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("androidx.annotation:annotation-experimental:1.4.1")
    compileOnly("androidx.media3:media3-common:1.8.0")
    compileOnly("androidx.media3:media3-exoplayer:1.8.0")
    compileOnly("androidx.media3:media3-extractor:1.8.0")
    compileOnly("androidx.media3:media3-effect:1.8.0")
    compileOnly("com.google.guava:guava:33.3.1-android")
}
