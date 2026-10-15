package com.sickworm.intellij.jugg.ai.mcp.actions

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

/**
 * Builds a sparse JSON artifact from the normalized hierarchy without modifying its source.
 * Interaction mode preserves the former HTML node selection and child promotion; UI mode
 * preserves the captured tree. Retained nodes share the same fields in both modes.
 */
internal class LayoutJsonConverter {

    /** Keep node fields compact while starting each hierarchy node on its own line. */
    fun serialize(root: JsonObject): String = buildString { appendJson(root, false) }

    private fun StringBuilder.appendJson(element: JsonElement, node: Boolean) {
        when {
            element.isJsonObject -> {
                if (node) append('\n')
                append('{')
                element.asJsonObject.entrySet().forEachIndexed { index, (key, value) ->
                    if (index > 0) append(',')
                    append(JsonPrimitive(key)).append(':')
                    appendJson(value, key == "root" || key == "children")
                }
                append('}')
            }
            element.isJsonArray -> {
                append('[')
                element.asJsonArray.forEachIndexed { index, value ->
                    if (index > 0) append(',')
                    appendJson(value, node)
                }
                append(']')
            }
            else -> append(element)
        }
    }

    fun convert(root: JsonObject, mode: String): JsonObject {
        val output = root.deepCopy()
        output.addProperty("mode", mode)
        if (mode == "interaction") {
            output.getArrayOrNull("windows")?.forEach { window ->
                window.asJsonObjectOrNull()?.get("root")?.asJsonObjectOrNull()?.let { pruneChildren(it) }
            }
        }
        return output
    }

    private fun pruneChildren(node: JsonObject) {
        val children = node.getArrayOrNull("children") ?: return
        val retained = JsonArray()
        children.forEach { child -> child.asJsonObjectOrNull()?.let { appendNode(retained, it) } }
        node.remove("children")
        if (retained.size() > 0) node.add("children", retained)
    }

    private fun appendNode(output: JsonArray, node: JsonObject) {
        if (isPruned(node)) {
            node.getArrayOrNull("children")?.forEach { child ->
                child.asJsonObjectOrNull()?.let { appendNode(output, it) }
            }
            return
        }
        pruneChildren(node)
        output.add(node)
    }

    private fun isPruned(node: JsonObject): Boolean {
        if (!node.get("message")?.asStringOrNull().isNullOrEmpty()) return false
        if (!node.get("text")?.asStringOrNull().isNullOrEmpty()) return false
        if (!node.get("contentDesc")?.asStringOrNull().isNullOrEmpty()) return false
        if (node.get("clickable")?.runCatching { asBoolean }?.getOrDefault(false) == true) return false
        val id = node.get("id")?.asStringOrNull()
        // The former HTML predicate also pruned non-container nodes without semantic content.
        return id.isNullOrEmpty() || id.startsWith("_vir_id_")
    }

    private fun JsonElement.asJsonObjectOrNull(): JsonObject? = if (isJsonObject) asJsonObject else null

    private fun JsonElement.asStringOrNull(): String? = runCatching { asString }.getOrNull()

    private fun JsonObject.getArrayOrNull(key: String): JsonArray? = get(key)?.takeIf { it.isJsonArray }?.asJsonArray
}
