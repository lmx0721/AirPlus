package net.airplus.ui.client.clickgui.opai

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.airplus.file.FileManager
import net.airplus.ui.client.clickgui.opai.OpaiCompat.Constants
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Persists Opai ClickGUI column positions ("opai:<Category>" -> x/y) in opai-layout.json.
 * The new single-window layout keeps every column draggable, so only positions are stored.
 */
object OpaiLayoutState {

    private const val FILE_NAME = "opai-layout.json"
    private val GSON = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    /** Returns the stored (x, y) for [id], or the given defaults when absent/corrupt. */
    fun loadColumn(id: String, defaultX: Float, defaultY: Float): Pair<Float, Float> {
        val root = readRoot() ?: return defaultX to defaultY
        val panelStates = root.getAsJsonObject("panels") ?: return defaultX to defaultY
        val element = panelStates.get(id) ?: return defaultX to defaultY
        if (!element.isJsonObject) return defaultX to defaultY
        val `object` = element.asJsonObject
        return readFloat(`object`, "x", defaultX) to readFloat(`object`, "y", defaultY)
    }

    fun saveColumns(states: Map<String, Pair<Float, Float>>) {
        val root = JsonObject()
        root.addProperty("version", 2)
        val panelStates = JsonObject()
        for ((id, position) in states) {
            val `object` = JsonObject()
            `object`.addProperty("x", position.first)
            `object`.addProperty("y", position.second)
            panelStates.add(id, `object`)
        }
        root.add("panels", panelStates)

        val file = getFile()
        try {
            Files.createDirectories(file.parent)
            Files.write(file, GSON.toJson(root).toByteArray(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)
        } catch (e: IOException) {
            Constants.LOGGER.error("保存 Opai GUI 布局失败", e)
        }
    }

    private fun readRoot(): JsonObject? {
        val file = getFile()
        if (!Files.exists(file)) return null
        return try {
            val parsed = JsonParser().parse(String(Files.readAllBytes(file), StandardCharsets.UTF_8))
            parsed?.takeIf { it.isJsonObject }?.asJsonObject
        } catch (e: Exception) {
            Constants.LOGGER.error("读取 Opai GUI 布局失败", e)
            null
        }
    }

    private fun getFile(): Path = FileManager.settingsDir.toPath().resolve(FILE_NAME)

    private fun readFloat(`object`: JsonObject, key: String, fallback: Float): Float {
        val value = `object`.get(key)
        if (value == null || !value.isJsonPrimitive) return fallback
        return try {
            value.asFloat
        } catch (ignored: Exception) {
            fallback
        }
    }
}
