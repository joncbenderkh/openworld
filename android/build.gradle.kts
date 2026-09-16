import java.text.SimpleDateFormat
import java.util.Date

plugins {
    id("com.android.application")
    kotlin("android")
}

val gdxVersion = "1.12.1"

// A short git commit hash plus the time of this build - unique per build,
// unlike versionName/versionCode which only change on a deliberate bump.
// Useful for telling apart two installs of the same version, e.g. when
// checking whether a device actually picked up the latest push.
fun buildId(): String {
    val sha = try {
        val process = ProcessBuilder("git", "rev-parse", "--short=8", "HEAD")
            .directory(rootDir)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor() == 0 && output.isNotBlank()) output else "unknown"
    } catch (e: Exception) {
        "unknown"
    }
    val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
    return "$sha-$timestamp"
}

android {
    namespace = "dev.joncbender.openworld"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.joncbender.openworld"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "BUILD_ID", "\"${buildId()}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("libs")
        }
    }

    packaging {
        resources.excludes += "META-INF/robovm/ios/robovm.xml"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

val natives: Configuration by configurations.creating

dependencies {
    implementation(project(":core"))
    implementation("com.badlogicgames.gdx:gdx-backend-android:$gdxVersion")

    natives("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-armeabi-v7a")
    natives("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-arm64-v8a")
    natives("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-x86")
    natives("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-x86_64")
}

// gdx-platform natives are plain jars with .so files at their root - AGP only
// merges native libs it finds under a jniLibs source dir, so extract them there.
val copyAndroidNatives by tasks.registering {
    val abiOutputDirs = mapOf(
        "natives-armeabi-v7a" to "armeabi-v7a",
        "natives-arm64-v8a" to "arm64-v8a",
        "natives-x86" to "x86",
        "natives-x86_64" to "x86_64",
    )
    doFirst {
        abiOutputDirs.values.forEach { abi -> file("libs/$abi").mkdirs() }
        natives.files.forEach { jar ->
            val abi = abiOutputDirs.entries.firstOrNull { jar.name.contains(it.key) }?.value
            if (abi != null) {
                copy {
                    from(zipTree(jar))
                    into(file("libs/$abi"))
                    include("*.so")
                }
            }
        }
    }
}

tasks.matching { it.name.contains("merge") && it.name.contains("JniLibFolders") }
    .configureEach { dependsOn(copyAndroidNatives) }
