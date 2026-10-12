plugins {
    kotlin("jvm")
    application
}

val gdxVersion = "1.12.1"

dependencies {
    implementation(project(":core"))
    implementation("com.badlogicgames.gdx:gdx-backend-lwjgl3:$gdxVersion")
    implementation("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-desktop")

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass = "dev.joncbender.openworld.DesktopLauncherKt"
    // Unlike a phone, a desktop JVM can be given the memory; the default heap is a quarter of RAM.
    // Run with e.g. `./gradlew :desktop:run --args="--max-near-patches=1024"`; pass -Xmx via JAVA_OPTS.
}

tasks.test {
    useJUnitPlatform()
}
