plugins {
    id("com.android.application") version "9.4.0" apply false
    kotlin("jvm") version "2.4.10" apply false
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}
