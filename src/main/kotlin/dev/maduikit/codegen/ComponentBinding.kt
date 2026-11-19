package dev.maduikit.codegen

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

internal class BindingProblem(val nodeId: String, message: String) : IllegalArgumentException(message)
internal data class ComponentBinding(val rootId: String, val instanceId: String, val componentId: String,
    val componentKey: String, val labelProperty: String, val enabledProperty: String,
    val labelNodeId: String, val enabled: Boolean, val mapHash: String) {
    fun receipt() = buildJsonObject {
        put("version", 1); put("instanceId", instanceId); put("componentId", componentId)
        put("componentKey", componentKey); put("labelProperty", labelProperty)
        put("enabledProperty", enabledProperty); put("labelNodeId", labelNodeId); put("enabled", enabled)
    }
}

internal fun readComponentBinding(path: Path, value: JsonElement, target: String): ComponentBinding {
    var claimed = target
    fun demand(condition: Boolean, message: String) { if (!condition) throw BindingProblem(claimed, message) }
    demand(Files.isRegularFile(path) && !Files.isSymbolicLink(path) && Files.size(path) in 1..4096, "Invalid binding sidecar file")
    val bytes = Files.readAllBytes(path)
    val raw = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    validateBindingJson(raw)
    val map = Json.parseToJsonElement(raw) as? JsonObject ?: throw BindingProblem(claimed, "Expected binding object")
    val b = map["binding"] as? JsonObject ?: throw BindingProblem(claimed, "Missing binding")
    claimed = (b["instanceId"] as? JsonPrimitive)?.contentOrNull ?: target
    val keys = setOf("instanceId", "componentKey", "labelProperty", "enabledProperty", "labelNodeId")
    demand(map.keys == setOf("schemaVersion", "binding") && map["schemaVersion"] == JsonPrimitive(1) && b.keys == keys, "Unsupported binding schema")
    fun field(k: String): String {
        val p = b[k] as? JsonPrimitive
        demand(p?.isString == true && p.content.length in 1..128, "Invalid binding field $k")
        return p!!.content
    }
    claimed = field("instanceId")
    val key = field("componentKey"); val labelKey = field("labelProperty"); val enabledKey = field("enabledProperty"); val textId = field("labelNodeId")
    demand(Regex("[A-Za-z0-9_-]+").matches(key) && labelKey != enabledKey, "Invalid or colliding binding keys")
    val file = value as? JsonObject ?: throw BindingProblem(claimed, "Expected Figma snapshot")
    val entry = (file["nodes"] as? JsonObject)?.get(target) as? JsonObject ?: throw BindingProblem(claimed, "Missing selected node")
    val doc = entry["document"] as? JsonObject ?: throw BindingProblem(claimed, "Missing root")
    fun JsonObject.str(k: String) = (get(k) as? JsonPrimitive)?.takeIf { it.isString }?.content
    demand("components" !in file && doc.str("type") == "FRAME" && doc.str("layoutMode") == "HORIZONTAL", "Require one unambiguous selection metadata map and horizontal root")
    val children = doc["children"] as? JsonArray
    val instance = children?.singleOrNull() as? JsonObject ?: throw BindingProblem(claimed, "Require one direct expanded instance")
    demand(instance.str("id") == claimed && instance.str("type") == "INSTANCE", "Claimed instance missing")
    val allowed = setOf("id","type","name","componentId","componentProperties","layoutMode","layoutWrap","layoutSizingHorizontal","layoutSizingVertical","absoluteBoundingBox","itemSpacing","primaryAxisAlignItems","counterAxisAlignItems","paddingLeft","paddingRight","paddingTop","paddingBottom","fills","children","cornerRadius","rectangleCornerRadii","clipsContent")
    demand(instance.keys.all { it in allowed }, "Unsupported claimed appearance, action or variant")
    demand(instance.str("layoutMode") == "HORIZONTAL" && instance.str("layoutSizingHorizontal") == "FIXED" && instance.str("layoutSizingVertical") == "FIXED", "Require fixed horizontal slot")
    val cid = instance.str("componentId") ?: throw BindingProblem(claimed, "Missing componentId")
    val components = entry["components"] as? JsonObject ?: throw BindingProblem(claimed, "Missing authoritative components")
    demand(components.values.all { it is JsonObject } && (components[cid] as? JsonObject)?.str("key") == key && components.values.count { (it as? JsonObject)?.str("key") == key } == 1, "Missing, mismatched or ambiguous authoritative component key")
    val properties = instance["componentProperties"] as? JsonObject ?: throw BindingProblem(claimed, "Missing typed properties")
    demand(properties.keys == setOf(labelKey, enabledKey), "Unknown or changed property key")
    val label = properties[labelKey] as? JsonObject ?: throw BindingProblem(claimed, "Invalid Label")
    val enabled = properties[enabledKey] as? JsonObject ?: throw BindingProblem(claimed, "Invalid Enabled")
    demand(label.keys == setOf("type","value") && label.str("type") == "TEXT" && label.str("value") != null, "Label must be typed TEXT")
    val enabledValue = enabled["value"] as? JsonPrimitive
    demand(enabled.keys == setOf("type","value") && enabled.str("type") == "BOOLEAN" && enabledValue?.isString == false && enabledValue.booleanOrNull != null, "Enabled must be typed BOOLEAN")
    val text = (instance["children"] as? JsonArray)?.singleOrNull() as? JsonObject ?: throw BindingProblem(claimed, "Require complete single Text slot")
    demand(text.keys.all { it in setOf("id","name","type","characters","style","fills","layoutSizingHorizontal","layoutSizingVertical") }, "Unsupported slot appearance")
    demand(text.str("id") == textId && text.str("type") == "TEXT" && text.str("characters") == label.str("value"), "Label and slot disagree")
    val style = text["style"] as? JsonObject ?: throw BindingProblem(claimed, "Missing slot style")
    demand(style.keys.all { it in setOf("fontFamily","fontWeight","fontSize","textAutoResize","lineHeightPx","lineHeightUnit","letterSpacing") }, "Unsupported slot style")
    val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    return ComponentBinding(target, claimed, cid, key, labelKey, enabledKey, textId, enabledValue!!.boolean, hash)
}
