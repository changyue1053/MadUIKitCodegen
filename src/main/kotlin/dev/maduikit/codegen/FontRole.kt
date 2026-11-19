package dev.maduikit.codegen

import kotlinx.serialization.json.*
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Version 1 binds one named family to a required consumer-owned FontFamily. */
internal data class FontRole(val family: String, val role: String, val mapHash: String) {
    val parameter: String get() = "fontRole" + role.replaceFirstChar { it.uppercase() }
}

internal fun readFontRole(path: Path): FontRole {
    require(!Files.isSymbolicLink(path) && Files.isRegularFile(path) && Files.size(path) in 1..4096) {
        "Expected a regular, unlinked font map of 1..4096 bytes"
    }
    val bytes = Files.readAllBytes(path)
    val text = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
    // The intentionally narrow v1 vocabulary needs no JSON escapes. Reject them
    // before parsing so duplicate escaped keys cannot hide a binding collision.
    require('\\' !in text) { "Escaped mapping keys or values are not supported" }
    val keys = Regex("\"(schemaVersion|mappings|family|role)\"\\s*:")
        .findAll(text).map { it.groupValues[1] }.toList()
    require(keys.size == 4 && keys.toSet().size == 4) { "Duplicate or nonliteral mapping keys" }
    val root = Json.parseToJsonElement(text) as? JsonObject
        ?: throw IllegalArgumentException("Expected font map object")
    require(root.keys == setOf("schemaVersion", "mappings") && root["schemaVersion"] == JsonPrimitive(1)) {
        "Invalid font map schema"
    }
    val entries = root["mappings"] as? JsonArray
        ?: throw IllegalArgumentException("Expected mappings array")
    require(entries.size == 1) { "Exactly one family/role binding is supported" }
    val entry = entries.single() as? JsonObject
        ?: throw IllegalArgumentException("Expected binding object")
    require(entry.keys == setOf("family", "role")) { "Unknown binding field" }
    fun string(key: String): String {
        val value = entry[key] as? JsonPrimitive
        require(value?.isString == true) { "Expected string binding field" }
        return value.content
    }
    val family = string("family")
    val role = string("role")
    require(family.length in 1..100 && family == family.trim() && Regex("[A-Za-z0-9 _-]+").matches(family)) {
        "Expected a nonempty ASCII named family"
    }
    require(family.lowercase() !in setOf("sans-serif", "serif", "monospace")) { "Generic family cannot be remapped" }
    require(role in setOf("body", "title", "label")) { "Unknown typography role" }
    val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    return FontRole(family, role, hash)
}
