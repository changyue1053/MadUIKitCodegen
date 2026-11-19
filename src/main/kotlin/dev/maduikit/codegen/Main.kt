package dev.maduikit.codegen

import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.Path
import kotlin.system.exitProcess

private const val GENERATOR_VERSION = "0.3.0-p5-preview"
private const val NORMALIZED_SCREEN = "SyntheticProfileScreen"
private const val FIGMA_SCREEN = "FigmaProfileScreen"
private const val MAX_INPUT_BYTES = 512 * 1024L
private val json = Json { prettyPrint = true }
private val idPattern = Regex("[A-Za-z0-9_:.\\-]{1,64}")
internal val figmaInstanceChildId = Regex("I[0-9]+:[0-9]+(?:;[0-9]+:[0-9]+)+")
internal val figmaComponentId = Regex("[0-9]+:[0-9]+")
private val argbPattern = Regex("#[0-9A-Fa-f]{8}")
private val numericPattern = Regex("-?(?:0|[1-9][0-9]{0,3})(?:\\.[0-9]{1,6})?")
private val boxFields = setOf("gap", "padding", "paddingEdges", "fillWidth", "width", "height", "background", "clip", "linearGradient", "mainCentered", "mainSpaceBetween", "crossCentered", "cornerRadii")
private val textFields = setOf("text", "fontSize", "color", "bold", "medium", "fillWidth", "width", "height", "lineHeight", "letterSpacing", "fontFamily")

internal data class Problem(val nodeId: String, val path: String, val message: String)
private data class Gradient(val axis: String, val start: BigDecimal, val end: BigDecimal,
    val stops: List<Pair<BigDecimal, String>>)
private data class Node(
    val id: String,
    val type: String,
    val text: String? = null,
    val gap: BigDecimal? = null,
    val padding: BigDecimal? = null,
    val fillWidth: Boolean = false,
    val height: BigDecimal? = null,
    val background: String? = null,
    val gradient: Gradient? = null,
    val fontSize: BigDecimal? = null,
    val color: String? = null,
    val bold: Boolean = false,
    val medium: Boolean = false,
    val width: BigDecimal? = null,
    val paddingEdges: List<BigDecimal>? = null,
    val clip: Boolean = false,
    val cornerRadii: List<BigDecimal>? = null,
    val lineHeight: BigDecimal? = null,
    val letterSpacing: BigDecimal? = null,
    val fontFamily: String? = null,
    val fontRole: String? = null,
    val mainCentered: Boolean = false,
    val mainSpaceBetween: Boolean = false,
    val crossCentered: Boolean = false,
    val sourceComponentId: String? = null,
    val children: List<Node> = emptyList(),
    val resourceName: String? = null,
)

private class Reader(private val asset: ExplicitAsset?, private val fontRole: FontRole? = null) {
    val problems = mutableListOf<Problem>()
    private val ids = mutableSetOf<String>()

    private fun error(id: String, path: String, message: String) {
        problems += Problem(id, path, message)
    }

    private fun checkKeys(obj: JsonObject, allowed: Set<String>, id: String, path: String) {
        (obj.keys - allowed).sorted().forEach {
            error(id, "$path.$it", "Unsupported field")
        }
    }

    fun document(value: JsonElement): Node? {
        val root = value as? JsonObject ?: run { error("document", "$", "Expected document object"); return null }
        checkKeys(root, setOf("schemaVersion", "frame"), "document", "$")
        if (root["schemaVersion"] != JsonPrimitive(1)) {
            error("document", "$.schemaVersion", "Only integer schemaVersion 1 is supported")
        }
        val frame = node(root["frame"], "$.frame", 0)
        if (frame != null && frame.type !in setOf("Column", "Row")) {
            error(frame.id, "$.frame.type", "The root Frame must be a Column or a Row")
        }
        return frame
    }

    private fun node(value: JsonElement?, path: String, depth: Int, parentWidthBounded: Boolean = false): Node? {
        if (depth > 24) { error("document", path, "Frame tree depth exceeds 24"); return null }
        val obj = value as? JsonObject ?: run { error("document", path, "Expected node object"); return null }
        val rawId = (obj["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val id = if (rawId != null && (idPattern.matches(rawId) ||
            (rawId.length <= 256 && figmaInstanceChildId.matches(rawId)))) rawId else path
        if (id != rawId) error(id, "$path.id", "Expected ID containing 1–64 ASCII letters, numbers, _, :, . or -, or a canonical Figma instance descendant ID")
        if (!ids.add(id)) error(id, "$path.id", "Duplicate node ID")
        if (ids.size > 300) { error(id, path, "Frame exceeds 300 nodes"); return null }

        fun string(name: String, required: Boolean = false): String? {
            val field = obj[name]
            if (field == null && !required) return null
            val text = (field as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (text == null || text.length > 2000) {
                error(id, "$path.$name", "Expected string no longer than 2000 characters")
                return null
            }
            return text
        }

        fun number(name: String, min: Int = 0, max: Int = 2048): BigDecimal? {
            val field = obj[name] ?: return null
            val raw = (field as? JsonPrimitive)?.takeUnless { it.isString }?.content
            val result = raw?.takeIf(numericPattern::matches)?.toBigDecimalOrNull()
            if (result == null || result !in min.toBigDecimal()..max.toBigDecimal()) {
                error(id, "$path.$name", "Expected a decimal number in $min..$max with at most six fractional digits")
                return null
            }
            return result
        }

        fun flag(name: String): Boolean {
            val field = obj[name] ?: return false
            return when (field) {
                JsonPrimitive(true) -> true
                JsonPrimitive(false) -> false
                else -> { error(id, "$path.$name", "Expected boolean"); false }
            }
        }

        fun color(name: String): String? {
            val value = string(name) ?: return null
            if (!argbPattern.matches(value)) {
                error(id, "$path.$name", "Expected #AARRGGBB"); return null
            }
            return value.drop(1).uppercase()
        }

        fun edges(): List<BigDecimal>? {
            val raw = obj["paddingEdges"] ?: return null
            val values = (raw as? JsonArray)?.map { field ->
                (field as? JsonPrimitive)?.takeUnless { it.isString }?.content
                    ?.takeIf(numericPattern::matches)?.toBigDecimalOrNull()
            }
            if (values == null || values.size != 4 || values.any { it == null || it !in 0.toBigDecimal()..128.toBigDecimal() }) {
                error(id, "$path.paddingEdges", "Expected [left, top, right, bottom] padding in 0..128")
                return null
            }
            if (obj["padding"] != null) error(id, "$path.paddingEdges", "padding and paddingEdges are mutually exclusive")
            return values.filterNotNull()
        }

        fun cornerRadii(): List<BigDecimal>? {
            val raw = obj["cornerRadii"] ?: return null
            val values = (raw as? JsonArray)?.map { field ->
                (field as? JsonPrimitive)?.takeUnless { it.isString }?.content
                    ?.takeIf(numericPattern::matches)?.toBigDecimalOrNull()
            }
            if (values == null || values.size != 4 || values.any { it == null || it !in 0.toBigDecimal()..2048.toBigDecimal() }) {
                error(id, "$path.cornerRadii", "Expected four clockwise radii in 0..2048")
                return null
            }
            return values.filterNotNull().takeIf { it.any { n -> n.signum() > 0 } }
        }

        fun gradient(): Gradient? {
            val value = obj["linearGradient"] ?: return null
            val raw = value as? JsonObject
            val gradientPath = "$path.linearGradient"
            fun reject(message: String): Nothing? { error(id, gradientPath, message); return null }
            if (raw == null) return reject("Expected gradient object")
            checkKeys(raw, setOf("axis", "start", "end", "stops"), id, gradientPath)
            if (obj["background"] != null) return reject("Background and linearGradient are mutually exclusive")
            val axis = (raw["axis"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            fun unit(element: JsonElement?): BigDecimal? = (element as? JsonPrimitive)?.takeUnless { it.isString }
                ?.content?.takeIf(numericPattern::matches)?.toBigDecimalOrNull()
                ?.takeIf { it in BigDecimal.ZERO..BigDecimal.ONE }
            val start = unit(raw["start"])
            val end = unit(raw["end"])
            if (axis !in setOf("x", "y") || start == null || end == null || start.compareTo(end) == 0)
                return reject("Expected x/y axis and unequal start/end fractions in 0..1")
            val entries = raw["stops"] as? JsonArray
            if (entries == null || entries.size !in 2..16) return reject("Expected 2 to 16 color stops")
            val stops = mutableListOf<Pair<BigDecimal, String>>()
            for ((index, element) in entries.withIndex()) {
                val entry = element as? JsonObject ?: return reject("Invalid stop object")
                checkKeys(entry, setOf("position", "color"), id, "$gradientPath.stops[$index]")
                val position = unit(entry["position"])
                val color = (entry["color"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (position == null || color == null || !argbPattern.matches(color) ||
                    stops.lastOrNull()?.first?.let { position <= it } == true)
                    return reject("Expected strictly ascending fractions and #AARRGGBB stop colors")
                stops += position to color.drop(1).uppercase()
            }
            return Gradient(checkNotNull(axis), start, end, stops)
        }

        val type = string("type", true) ?: return null
        when (type) {
            "Column", "Row", "Box" -> {
                val allowed = if (type == "Box") boxFields - setOf("gap", "mainCentered", "mainSpaceBetween", "crossCentered") else boxFields + "sourceComponentId"
                checkKeys(obj, allowed + setOf("id", "type", "children"), id, path)
                val childField = obj["children"] as? JsonArray
                if (childField == null) error(id, "$path.children", "Expected children array")
                if (childField != null && childField.size > 150) {
                    error(id, "$path.children", "Each node supports at most 150 children")
                    return null
                }
                val width = number("width", min = 1)
                val height = number("height", min = 1)
                val gap = number("gap", max = 128)
                val padding = number("padding", max = 128)
                val paddingEdges = edges()
                val fillWidth = flag("fillWidth")
                val mainCentered = flag("mainCentered")
                val mainSpaceBetween = flag("mainSpaceBetween")
                // A fixed width produces finite constraints; fillWidth only preserves a known
                // bound. A top-level fillWidth opts into the documented bounded host contract.
                val widthBounded = width != null || (fillWidth && (depth == 0 || parentWidthBounded))
                val children = childField?.mapIndexedNotNull { idx, next ->
                    node(next, "$path.children[$idx]", depth + 1, type == "Column" && widthBounded)
                }.orEmpty()
                if (mainSpaceBetween) {
                    val row = type == "Row"
                    if (mainCentered || (gap != null && gap.signum() != 0))
                        error(id, "$path.mainSpaceBetween", "SPACE_BETWEEN cannot be combined with centering or a non-zero gap")
                    if ((row && !widthBounded) || (!row && height == null) || (fillWidth && width != null))
                        error(id, "$path.mainSpaceBetween", "SPACE_BETWEEN requires a fixed main axis or a row filling a bounded column")
                    if (children.size < 2)
                        error(id, "$path.mainSpaceBetween", "SPACE_BETWEEN requires at least two visible children")
                    val extent = if (row) width else height
                    val sizes = children.map { child ->
                        if (row && child.fillWidth) null else if (row) child.width else child.height
                    }
                    if (sizes.any { it == null })
                        error(id, "$path.mainSpaceBetween", "SPACE_BETWEEN requires children with fixed main-axis sizes")
                    if (extent != null && sizes.all { it != null }) {
                        val insets = paddingEdges?.let { if (row) it[0] + it[2] else it[1] + it[3] }
                            ?: (padding ?: BigDecimal.ZERO) * BigDecimal(2)
                        val used = sizes.filterNotNull().fold(insets, BigDecimal::add)
                        if (used > extent)
                            error(id, "$path.mainSpaceBetween", "SPACE_BETWEEN children and main-axis padding exceed the fixed extent")
                    }
                }
                val sourceComponentId = string("sourceComponentId")
                if (sourceComponentId != null && (sourceComponentId.length > 64 || !figmaComponentId.matches(sourceComponentId)))
                    error(id, "$path.sourceComponentId", "Expected canonical Figma component ID")
                return Node(
                    sourceComponentId = sourceComponentId,
                    mainCentered = mainCentered, mainSpaceBetween = mainSpaceBetween, crossCentered = flag("crossCentered"),
                    id = id, type = type, gap = gap,
                    padding = padding, fillWidth = fillWidth,
                    height = height, background = color("background"), children = children,
                    gradient = gradient(), width = width, paddingEdges = paddingEdges, clip = flag("clip"),
                    cornerRadii = cornerRadii(),
                )
            }
            "DecorativeImage" -> {
                checkKeys(obj, setOf("id", "type", "width", "height", "resourceName"), id, path)
                val resourceName = string("resourceName", required = true)
                val width = number("width", min = 1, max = 64)
                val height = number("height", min = 1, max = 64)
                if (asset == null || resourceName != asset.resourceName || id != asset.nodeId ||
                    width?.compareTo(asset.width.toBigDecimal()) != 0 || height?.compareTo(asset.height.toBigDecimal()) != 0) {
                    error(id, "$path.resourceName", "Image leaf requires exactly matching verified --asset-map binding")
                    return null
                }
                return Node(id = id, type = type, height = height, width = width, resourceName = resourceName)
            }
            "Text" -> {
                checkKeys(obj, textFields + setOf("id", "type") + (if (fontRole != null) setOf("fontRole") else emptySet()), id, path)
                val role = if (fontRole != null) string("fontRole") else null
                if (role != null && (role != fontRole?.role || obj["fontFamily"] != null))
                    error(id, "$path.fontRole", "Unbound or conflicting font role")
                val family = string("fontFamily")
                if (family != null && family !in setOf("SansSerif", "Serif", "Monospace"))
                    error(id, "$path.fontFamily", "Unsupported font family")
                val bold = flag("bold")
                val medium = flag("medium")
                if (bold && medium) error(id, "$path.medium", "Medium and bold cannot both be true")
                return Node(id, type, text = string("text", true), fillWidth = flag("fillWidth"),
                    fontSize = number("fontSize", 1, 128), color = color("color"), bold = bold, medium = medium,
                    width = number("width", min = 1), height = number("height", min = 1),
                    lineHeight = number("lineHeight", 1, 256), letterSpacing = number("letterSpacing", -100, 100),
                    fontFamily = family, fontRole = role)
            }
            "Button" -> {
                checkKeys(obj, setOf("id", "type", "text", "action", "fillWidth"), id, path)
                if (string("action", true) != "save") error(id, "$path.action", "Only the explicit save action is supported in P0")
                return Node(id, type, text = string("text", true), fillWidth = flag("fillWidth"))
            }
            else -> { error(id, "$path.type", "Unsupported node type: $type"); return null }
        }
    }
}

private fun kotlinString(value: String): String = buildString {
    append('"')
    for (c in value) when (c) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '$' -> append('\\').append('$')
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        else -> if (c < ' ' || Character.isSurrogate(c) || c == '\u2028' || c == '\u2029')
            append("\\u").append(c.code.toString(16).padStart(4, '0'))
        else append(c)
    }
    append('"')
}

private fun formatted(value: BigDecimal): String {
    val clean = value.stripTrailingZeros().toPlainString()
    return if ('.' in clean) "${clean}f" else clean
}

private fun fraction(value: BigDecimal) = value.stripTrailingZeros().toPlainString() + "f"
private fun Node.containsCenter(): Boolean = mainCentered || crossCentered || children.any(Node::containsCenter)

private fun Node.containsGradient(): Boolean = gradient != null || children.any(Node::containsGradient)

private fun Node.imageResources(): List<String> = listOfNotNull(resourceName) + children.flatMap(Node::imageResources)

private fun Node.containsImage(): Boolean = resourceName != null || children.any(Node::containsImage)

private fun Node.containsCorners(): Boolean = cornerRadii != null || children.any(Node::containsCorners)
private fun Node.containsRoundedClip(): Boolean = (clip && cornerRadii != null) || children.any(Node::containsRoundedClip)
private fun Node.containsRoundedUnclippedGradient(): Boolean =
    (!clip && gradient != null && cornerRadii != null) || children.any(Node::containsRoundedUnclippedGradient)

private class Emitter(private val function: String, private val fontRole: FontRole? = null) {
    private val lines = mutableListOf<String>()
    val source = linkedMapOf<String, Int>()
    val sourceComponents = linkedMapOf<String, String>()

    fun render(root: Node): String {
        lines += listOf(
            "// Generated by MadUIKitCodegen. Do not edit.",
            "package dev.maduikit.demo.generated",
            "",
            "import androidx.compose.foundation.background",
            "import androidx.compose.foundation.layout.Arrangement",
            "import androidx.compose.foundation.layout.Box",
            "import androidx.compose.foundation.layout.Column",
            "import androidx.compose.foundation.layout.Row",
            "import androidx.compose.foundation.layout.fillMaxWidth",
            "import androidx.compose.foundation.layout.height",
            "import androidx.compose.foundation.layout.padding",
            "import androidx.compose.material3.Button",
            "import androidx.compose.material3.Text",
            "import androidx.compose.runtime.Composable",
            "import androidx.compose.ui.Modifier",
            "import androidx.compose.ui.graphics.Color",
            "import androidx.compose.ui.text.font.FontWeight",
            "import androidx.compose.ui.unit.dp",
            "import androidx.compose.ui.unit.sp",
            "import androidx.compose.foundation.layout.absolutePadding",
            "import androidx.compose.foundation.layout.width",
            "import androidx.compose.ui.draw.clipToBounds",
            "import androidx.compose.ui.text.font.FontFamily",
        )
        if (root.containsCorners()) lines += "import androidx.compose.foundation.shape.AbsoluteRoundedCornerShape"
        if (root.containsRoundedClip()) lines += "import androidx.compose.ui.draw.clip"
        if (root.containsRoundedUnclippedGradient()) lines += listOf(
            "import androidx.compose.ui.graphics.Shape",
            "import androidx.compose.ui.graphics.drawOutline",
        )
        if (root.containsImage()) {
            lines += "import androidx.compose.foundation.Image"
            lines += "import androidx.compose.ui.layout.ContentScale"
            lines += "import org.jetbrains.compose.resources.painterResource"
            lines += "import dev.maduikit.demo.resources.Res"
            lines += root.imageResources().distinct().sorted().map { "import dev.maduikit.demo.resources.$it" }
        }
        if (root.containsCenter()) lines += "import androidx.compose.ui.Alignment"
        if (root.containsGradient()) lines += listOf(
            "import androidx.compose.ui.draw.drawWithCache",
            "import androidx.compose.ui.geometry.Offset",
            "import androidx.compose.ui.graphics.Brush",
        )
        lines += listOf(
            "",
            "@Composable",
            when {
                function == NORMALIZED_SCREEN -> "fun $function(onSave: () -> Unit = {}) {"
                fontRole != null -> "fun $function(${fontRole.parameter}: FontFamily) {"
                else -> "fun $function() {"
            },
        )
        emit(root, 1)
        lines += "}"
        if (root.containsGradient()) lines += listOf(
            "",
            "private fun Modifier.figmaAxisGradient(horizontal: Boolean, from: Float, to: Float,",
            if (root.containsRoundedUnclippedGradient())
                "    vararg stops: Pair<Float, Color>, shape: Shape? = null): Modifier = drawWithCache {"
            else "    vararg stops: Pair<Float, Color>): Modifier = drawWithCache {",
            "    val start = if (horizontal) Offset(size.width * from, 0f) else Offset(0f, size.height * from)",
            "    val end = if (horizontal) Offset(size.width * to, 0f) else Offset(0f, size.height * to)",
            "    val brush = Brush.linearGradient(*stops, start = start, end = end)",
            if (root.containsRoundedUnclippedGradient())
                "    val outline = shape?.createOutline(size, layoutDirection, this)"
            else "    onDrawBehind { drawRect(brush) }",
            *if (root.containsRoundedUnclippedGradient()) arrayOf(
                "    onDrawBehind { if (outline == null) drawRect(brush) else drawOutline(outline, brush) }"
            ) else emptyArray(),
            "}",
        )
        return lines.joinToString("\n", postfix = "\n")
    }

    private fun emit(node: Node, depth: Int) {
        val indent = "    ".repeat(depth)
        source[node.id] = lines.size + 1
        node.sourceComponentId?.let { sourceComponents[node.id] = it }
        val shape = node.cornerRadii?.let {
            val names = listOf("topLeft", "topRight", "bottomRight", "bottomLeft")
            it.mapIndexed { i, r -> "${names[i]} = ${formatted(r)}.dp" }
                .joinToString(", ", "AbsoluteRoundedCornerShape(", ")")
        }
        val mods = buildList {
            if (node.fillWidth) add("fillMaxWidth()")
            node.width?.let { add("width(${formatted(it)}.dp)") }
            node.height?.let { add("height(${formatted(it)}.dp)") }
            if (node.clip) add(if (shape == null) "clipToBounds()" else "clip($shape)")
            node.background?.let {
                if (shape != null && !node.clip) add("background(Color(0x$it), $shape)")
                else add("background(Color(0x$it))")
            }
            node.gradient?.let { gradient ->
                val stops = gradient.stops.joinToString(", ") { (at, color) -> "${fraction(at)} to Color(0x$color)" }
                val shaped = if (shape != null && !node.clip) ", shape = $shape" else ""
                add("figmaAxisGradient(${gradient.axis == "x"}, ${fraction(gradient.start)}, ${fraction(gradient.end)}, $stops$shaped)")
            }
            node.padding?.let { add("padding(${formatted(it)}.dp)") }
            node.paddingEdges?.let {
                add("absolutePadding(left = ${formatted(it[0])}.dp, top = ${formatted(it[1])}.dp, right = ${formatted(it[2])}.dp, bottom = ${formatted(it[3])}.dp)")
            }
        }
        val modifier = mods.takeIf { it.isNotEmpty() }?.joinToString(".", "Modifier.")
        when (node.type) {
            "Column", "Row", "Box" -> {
                val params = buildList {
                    modifier?.let { add("modifier = $it") }
                    if (node.type != "Box") {
                        val arrangement = if (node.type == "Row") "horizontalArrangement" else "verticalArrangement"
                        val row = node.type == "Row"
                        val mainAlignment = if (row) "CenterHorizontally" else "CenterVertically"
                        val crossAlignment = if (row) "CenterVertically" else "CenterHorizontally"
                        if (node.mainSpaceBetween) {
                            val distribution = if (row) "Arrangement.Absolute.SpaceBetween" else "Arrangement.SpaceBetween"
                            add("$arrangement = $distribution")
                        } else if (node.mainCentered) {
                            val gap = node.gap ?: BigDecimal.ZERO
                            add("$arrangement = Arrangement.spacedBy(${formatted(gap)}.dp, Alignment.$mainAlignment)")
                        } else {
                            node.gap?.let { add("$arrangement = Arrangement.spacedBy(${formatted(it)}.dp)") }
                        }
                        if (node.crossCentered) {
                            val alignment = if (row) "verticalAlignment" else "horizontalAlignment"
                            add("$alignment = Alignment.$crossAlignment")
                        }
                    }
                }
                val call = if (params.isEmpty()) node.type else node.type + params.joinToString(", ", "(", ")")
                lines += "$indent$call {"
                node.children.forEach { emit(it, depth + 1) }
                lines += "$indent}"
            }
            "DecorativeImage" -> {
                val name = checkNotNull(node.resourceName)
                lines += "${indent}Image(painter = painterResource(Res.drawable.$name), contentDescription = null, modifier = ${checkNotNull(modifier)}, contentScale = ContentScale.FillBounds)"
            }
            "Text" -> {
                val params = buildList {
                    add("text = ${kotlinString(checkNotNull(node.text))}")
                    modifier?.let { add("modifier = $it") }
                    node.fontSize?.let { add("fontSize = ${formatted(it)}.sp") }
                    node.letterSpacing?.let { add("letterSpacing = ${formatted(it)}.sp") }
                    node.lineHeight?.let { add("lineHeight = ${formatted(it)}.sp") }
                    node.fontFamily?.let { add("fontFamily = FontFamily.$it") }
                    node.fontRole?.let { add("fontFamily = ${checkNotNull(fontRole).parameter}") }
                    node.color?.let { add("color = Color(0x$it)") }
                    if (node.bold) add("fontWeight = FontWeight.Bold")
                    if (node.medium) add("fontWeight = FontWeight.Medium")
                }
                lines += indent + params.joinToString(", ", "Text(", ")")
            }
            "Button" -> {
                val mod = modifier?.let { ", modifier = $it" }.orEmpty()
                lines += "${indent}Button(onClick = onSave$mod) { Text(${kotlinString(checkNotNull(node.text))}) }"
            }
            else -> error("Validated node cannot have unsupported type")
        }
    }
}

internal fun sha256(content: ByteArray) = MessageDigest.getInstance("SHA-256").digest(content)
    .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

private fun writeJson(path: Path, value: JsonElement) {
    Files.writeString(path, json.encodeToString(JsonElement.serializer(), value) + "\n", UTF_8)
}

private fun run(input: Path, output: Path, figmaNode: String? = null, fitWidth: Boolean = false, flattenInstances: Boolean = false, assetMap: Path? = null, fontMap: Path? = null): Int {
    val screen = if (figmaNode == null) NORMALIZED_SCREEN else FIGMA_SCREEN
    val kotlinFile = "$screen.generated.kt"
    if (!Files.isRegularFile(input) || Files.size(input) > MAX_INPUT_BYTES) {
        System.err.println("Input must be a regular file no larger than $MAX_INPUT_BYTES bytes")
        return 2
    }
    val bytes = Files.readAllBytes(input)
    val inputHash = sha256(bytes)
    var assetIssue: String? = null
    val asset = assetMap?.let {
        try {
            readExplicitAsset(it)
        } catch (err: java.io.IOException) {
            assetIssue = "Cannot read map or local PNG: ${err.javaClass.simpleName}"
            null
        } catch (err: IllegalArgumentException) {
            assetIssue = if (err.javaClass == IllegalArgumentException::class.java) err.message ?: "Invalid asset map or PNG"
                else "Invalid asset map or PNG"
            null
        }
    }
    var fontIssue: String? = null
    val fontRole = fontMap?.let {
        try {
            readFontRole(it)
        } catch (_: java.io.IOException) {
            fontIssue = "Cannot read font map"
            null
        } catch (_: IllegalArgumentException) {
            fontIssue = "Invalid font map: require one named family and a known unique role"
            null
        }
    }
    val reader = Reader(asset, fontRole)
    fontIssue?.let { reader.problems += Problem("document", "$.fontMap", it) }
    assetIssue?.let { reader.problems += Problem("document", "$.assetMap", it) }
    var figmaVersion: String? = null
    val root = try {
        val value = json.parseToJsonElement(UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString())
        if (assetIssue != null || fontIssue != null) null
        else if (figmaNode == null) reader.document(value)
        else {
            val result = normalizeFigmaNodes(value, figmaNode, fitWidth, flattenInstances, asset, fontRole)
            figmaVersion = result.sourceVersion
            reader.problems.addAll(result.problems)
            if (result.normalized == null || result.problems.isNotEmpty()) null
            else {
                val normalizedPath = output.resolve("normalized-frame.json")
                Files.createDirectories(output)
                writeJson(normalizedPath, result.normalized)
                reader.document(result.normalized)
            }
        }
    } catch (_: CharacterCodingException) {
        reader.problems += Problem("document", "$", "Input is not valid UTF-8")
        null
    } catch (_: IllegalArgumentException) {
        reader.problems += Problem("document", "$", "Invalid JSON")
        null
    }
    Files.createDirectories(output)
    if (root != null && reader.problems.isEmpty() && asset != null) {
        try {
            stageAsset(output, asset)
        } catch (err: java.io.IOException) {
            reader.problems += Problem(asset.nodeId, "$.assetMap.output", "Failed to write confined resource: ${err.javaClass.simpleName}")
        } catch (_: IllegalArgumentException) {
            reader.problems += Problem(asset.nodeId, "$.assetMap.output", "Resource output directory must not be a symlink")
        }
    }
    val diagnostics = buildJsonObject {
        put("schemaVersion", 1)
        put("inputSha256", inputHash)
        put("ok", root != null && reader.problems.isEmpty())
        putJsonArray("errors") {
            reader.problems.forEach { problem ->
                add(buildJsonObject {
                    put("nodeId", problem.nodeId)
                    put("path", problem.path)
                    put("message", problem.message)
                })
            }
        }
    }
    writeJson(output.resolve("diagnostics.json"), diagnostics)
    if (root == null || reader.problems.isNotEmpty()) {
        System.err.println("Generation failed; see diagnostics.json. Any earlier output is not from this run.")
        return 2
    }
    val emitter = Emitter(screen, fontRole)
    val code = emitter.render(root)
    Files.writeString(output.resolve(kotlinFile), code, UTF_8)
    writeJson(output.resolve("source-map.json"), buildJsonObject {
        put("schemaVersion", 1)
        put("file", kotlinFile)
        putJsonObject("nodeLines") { emitter.source.forEach { (id, line) -> put(id, line) } }
        if (emitter.sourceComponents.isNotEmpty()) putJsonObject("instanceComponents") {
            emitter.sourceComponents.forEach { (instanceId, componentId) -> put(instanceId, componentId) }
        }
    })
    writeJson(output.resolve("manifest.json"), buildJsonObject {
        put("schemaVersion", 1)
        put("generatorVersion", GENERATOR_VERSION)
        put("target", "cmp")
        put("frameId", root.id)
        if (figmaNode != null) {
            put("inputFormat", "figma-rest-file-nodes")
            put("adapterVersion", FIGMA_ADAPTER_VERSION)
            if (fitWidth) put("rootWidthMode", "fit-host")
            if (flattenInstances) put("instanceMode", "expanded-visual-v1")
            figmaVersion?.let { put("figmaSourceVersion", it) }
        }
        put("inputSha256", inputHash)
        put("sdkRequired", false)
        if (fontRole != null) {
            put("fontMapSha256", fontRole.mapHash)
            putJsonObject("fontRoleBinding") { put("version", 1); put("family", fontRole.family); put("role", fontRole.role); put("parameter", fontRole.parameter) }
        }
        if (asset != null) {
            put("assetMapSha256", asset.mapHash)
            putJsonObject("decorativeAsset") {
                put("nodeId", asset.nodeId)
                put("file", asset.outputPath)
                put("width", asset.width)
                put("height", asset.height)
                put("sha256", asset.hash)
            }
        }
        putJsonObject("files") {
            put(kotlinFile, sha256(code.toByteArray(UTF_8)))
            if (asset != null) put(asset.outputPath, asset.hash)
        }
    })
    return 0
}

private fun stageAsset(output: Path, asset: ExplicitAsset) {
    fun directory(path: Path): Path {
        if (!Files.exists(path, NOFOLLOW_LINKS)) Files.createDirectory(path)
        require(Files.isDirectory(path, NOFOLLOW_LINKS))
        return path
    }
    val drawable = directory(directory(output.resolve("composeResources")).resolve("drawable"))
    val temp = Files.createTempFile(drawable, ".maduikit-", ".tmp")
    try {
        Files.write(temp, asset.bytes)
        Files.move(temp, drawable.resolve("${asset.resourceName}.png"), REPLACE_EXISTING)
    } finally {
        Files.deleteIfExists(temp)
    }
}

fun main(args: Array<String>) {
    val normalized = args.size == 5 && args[0] == "generate" && args[1] == "--input" && args[3] == "--out"
    var fitWidth = false
    var flattenInstances = false
    var assetMapArgument: String? = null
    var fontMapArgument: String? = null
    var validFlags = true
    var i = 7
    while (i < args.size && validFlags) {
        when (args[i++]) {
            "--fit-width" -> if (fitWidth) validFlags = false else fitWidth = true
            "--flatten-instances" -> if (flattenInstances) validFlags = false else flattenInstances = true
            "--font-map" -> if (fontMapArgument != null || i >= args.size || args[i].startsWith("--")) validFlags = false else fontMapArgument = args[i++]
            "--asset-map" -> if (assetMapArgument != null || i >= args.size || args[i].startsWith("--"))
                validFlags = false else assetMapArgument = args[i++]
            else -> validFlags = false
        }
    }
    val figma = (args.size >= 7 && validFlags) && args[0] == "generate-figma" && args[1] == "--input" &&
        args[3] == "--node" && args[5] == "--out"
    if (!normalized && !figma) {
        System.err.println("Usage: maduikit-codegen generate --input <normalized-frame.json> --out <directory>")
        System.err.println("   or: maduikit-codegen generate-figma --input <file-nodes.json> --node <id> --out <directory> [--fit-width] [--flatten-instances] [--asset-map <local-map.json>] [--font-map <font-map.json>]")
        exitProcess(2)
    }
    try {
        if (figma) exitProcess(run(Path(args[2]), Path(args[6]), args[4], fitWidth, flattenInstances, assetMapArgument?.let { Path(it) }, fontMapArgument?.let { Path(it) }))
        exitProcess(run(Path(args[2]), Path(args[4])))
    } catch (err: java.io.IOException) {
        System.err.println("File I/O failed: ${err.javaClass.simpleName}")
        exitProcess(2)
    } catch (_: java.nio.file.InvalidPathException) {
        System.err.println("Invalid file path")
        exitProcess(2)
    }
}
