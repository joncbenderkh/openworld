plugins {
    kotlin("jvm")
    `java-library`
}

val gdxVersion = "1.12.1"

dependencies {
    api("com.badlogicgames.gdx:gdx:$gdxVersion")

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
}

tasks.test {
    useJUnitPlatform()
}
