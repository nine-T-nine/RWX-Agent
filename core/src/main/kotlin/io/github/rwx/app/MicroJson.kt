package io.github.rwx.app

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path

/** JSON bridge for micro evidence; uses the application's existing serialization dependency. */
object MicroJson {
    @JvmStatic fun read(path: Path): Map<String, Any?> =
        value(Json.parseToJsonElement(Files.readString(path))) as? Map<String, Any?>
            ?: error("Expected a JSON object")

    @JvmStatic fun encode(value: Any?): String = element(value).toString()

    private fun value(element: JsonElement): Any? = when (element) {
        JsonNull -> null
        is JsonObject -> element.mapValues { value(it.value) }
        is JsonArray -> element.map { value(it) }
        is JsonPrimitive -> if (element.isString) element.content else
            element.booleanOrNull ?: element.longOrNull ?: element.doubleOrNull ?: element.content
    }

    private fun element(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to element(it.value) })
        is Iterable<*> -> JsonArray(value.map { element(it) })
        is Boolean -> JsonPrimitive(value)
        is Number -> if (value.toDouble().isFinite()) JsonPrimitive(value) else JsonNull
        else -> JsonPrimitive(value.toString())
    }
}
