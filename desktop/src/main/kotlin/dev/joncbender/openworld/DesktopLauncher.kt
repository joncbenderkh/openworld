package dev.joncbender.openworld

import com.badlogic.gdx.Files
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import java.io.File
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val options = try {
        LaunchOptions.parse(args)
    } catch (e: IllegalArgumentException) {
        System.err.println("openworld: ${e.message}")
        exitProcess(2)
    }

    val home = System.getProperty("user.home")
    val dataDir = File(System.getenv("XDG_DATA_HOME") ?: "$home/.local/share", "openworld")
    val configDir = File(System.getenv("XDG_CONFIG_HOME") ?: "$home/.config", "openworld")

    val config = Lwjgl3ApplicationConfiguration().apply {
        setTitle("openworld")
        setWindowedMode(1280, 800)
        useVsync(true)
        // The near/far/underlay layering relies on a 24-bit depth buffer.
        setBackBufferConfig(8, 8, 8, 8, 24, 0, 0)
        setPreferencesConfig(configDir.path, Files.FileType.Absolute)
    }
    Lwjgl3Application(DesktopApp(options, dataDir), config)
}
