package dk.kodeologi.virtualrace.engine

import dk.kodeologi.virtualrace.protocol.DansprintProtocol
import java.io.File
import java.util.Properties

/**
 * Persistence interface for race configuration that should be remembered across app runs.
 */
interface RaceSettingsStore {
    fun loadConfiguredLanesCount(): Int?
    fun saveConfiguredLanesCount(count: Int)
}

/**
 * Properties-file-backed settings store.
 *
 * Defaults to `${XDG_CONFIG_HOME:-~/.config}/virtual-race/settings.properties`
 * so remembered settings work regardless of working directory and do not dirty the project folder.
 */
class FileRaceSettingsStore(
    val settingsFile: File = defaultSettingsFile()
) : RaceSettingsStore {

    override fun loadConfiguredLanesCount(): Int? {
        return try {
            if (!settingsFile.isFile) return null
            val props = Properties()
            settingsFile.inputStream().use { props.load(it) }
            props.getProperty(KEY_CONFIGURED_LANES_COUNT)
                ?.trim()
                ?.toIntOrNull()
                ?.coerceIn(1, DansprintProtocol.MAX_LANES)
        } catch (_: Throwable) {
            null
        }
    }

    override fun saveConfiguredLanesCount(count: Int) {
        val clamped = count.coerceIn(1, DansprintProtocol.MAX_LANES)
        try {
            val props = Properties()
            if (settingsFile.isFile) {
                runCatching {
                    settingsFile.inputStream().use { props.load(it) }
                }
            }
            props.setProperty(KEY_CONFIGURED_LANES_COUNT, clamped.toString())
            settingsFile.parentFile?.mkdirs()
            settingsFile.outputStream().use { out ->
                props.store(out, "Kayak Virtual Race settings")
            }
        } catch (_: Throwable) {
            // Never crash the race engine if settings storage is unavailable
        }
    }

    companion object {
        const val KEY_CONFIGURED_LANES_COUNT = "configuredLanesCount"

        fun defaultSettingsFile(): File {
            val xdgConfigHome = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
            val baseDir = if (xdgConfigHome != null) {
                File(xdgConfigHome)
            } else {
                File(System.getProperty("user.home", "."), ".config")
            }
            return File(File(baseDir, "virtual-race"), "settings.properties")
        }
    }
}
