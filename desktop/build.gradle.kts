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
    // Run with e.g. `./gradlew :desktop:run --args="--max-near-patches=1024"`.
}

// `run` forks the game, and a -D given to Gradle stays with Gradle: pass the game's own settings on.
tasks.run {
    System.getProperties().stringPropertyNames()
        .filter { it.startsWith("openworld.") }
        .forEach { systemProperty(it, System.getProperty(it)) }
    // Heap for the forked game, e.g. -Popenworld.heap=4g (JAVA_OPTS reaches only the installed start script).
    (findProperty("openworld.heap") as String?)?.let { maxHeapSize = it }
}

tasks.test {
    useJUnitPlatform()
}
