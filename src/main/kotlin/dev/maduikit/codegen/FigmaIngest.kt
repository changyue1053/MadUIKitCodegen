package dev.maduikit.codegen

import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.math.RoundingMode

internal const val FIGMA_ADAPTER_VERSION = 2

internal data class FigmaResult(val normalized: JsonObject?, val sourceVersion: String?, val problems: List<Problem>)

internal fun normalizeFigmaNodes(value: JsonElement, target: String, fitWidth: Boolean = false, flattenInstances: Boolean = false, asset: ExplicitAsset? = null): FigmaResult =
    FigmaNodesReader(fitWidth, flattenInstances, asset).read(value, target)

private class FigmaNodesReader(private val fitWidth: Boolean, private val flattenInstances: Boolean, private val asset: ExplicitAsset?) {
    private val problems = mutableListOf<Problem>()
    private var visited = 0
    private var assetUsed = false

    fun read(value: JsonElement, target: String): FigmaResult {
        val idPattern = Regex("[0-9]+:[0-9]+")
        if (!idPattern.matches(target) || target.length > 64) {
            problem("document", "$.nodes", "Expected canonical Figma node ID (example: 1:2)")
            return FigmaResult(null, null, problems)
        }
        val root = value as? JsonObject
        val sourceVersion = root?.str("version")?.takeIf { it.length in 1..128 && it.all { c -> c.isLetterOrDigit() || c in "-_." } }
        val metadata = "$.nodes[$target]"
        val entry = (root?.get("nodes") as? JsonObject)?.get(target) as? JsonObject
        val doc = entry?.get("document") as? JsonObject
        if (doc == null) {
            problem(target, metadata, "Requested node is absent or unavailable in a GET file nodes response")
            return FigmaResult(null, sourceVersion, problems)
        }
        if (root?.get("version") != null && sourceVersion == null) problem(target, "$.version", "Invalid response version")
        if (doc.str("id") != target) problem(target, "$metadata.document.id", "Document ID does not match requested node")
        if (doc.str("type") != "FRAME") problem(target, "$metadata.document.type", "Root document must be a FRAME")
        if (doc.flag("visible") == false) problem(target, "$metadata.document.visible", "Root Frame is hidden")
        val normalized = convert(doc, "$metadata.document", null, 0, true)?.let { frame ->
            buildJsonObject { put("schemaVersion", 1); put("frame", frame) }
        }
        if (asset != null && !assetUsed)
            problem(asset.nodeId, "$.assetMap.nodeId", "Mapped VECTOR must be a visible leaf of the requested Frame")
        return FigmaResult(normalized, sourceVersion, problems.toList())
    }

    private fun problem(id: String, path: String, reason: String) {
        problems += Problem(id, path, reason)
    }

    private fun convert(node: JsonObject, path: String, parent: String?, depth: Int, root: Boolean = false): JsonObject? {
        // Hidden descendants do not contribute pixels or layout in a Figma auto-layout frame.
        if (node.flag("visible") == false) return null
        val id = node.str("id") ?: "(unknown)"
        if (depth > 24 || ++visited > 300) {
            problem(id, path, "Frame exceeds the P1 limit of 300 nodes / 24 levels")
            return null
        }
        fun fail(field: String, reason: String) = problem(id, "$path.$field", reason)
        fun expect(field: String, allowed: Set<String>) {
            if (node[field] != null && node.str(field) !in allowed) fail(field, "Unsupported value: ${node.str(field) ?: "(not a string)"}")
        }
        fun zero(field: String) {
            if (node[field] != null && node.num(field)?.compareTo(BigDecimal.ZERO) != 0) fail(field, "Nonzero or invalid value is not supported")
        }
        fun absent(field: String) {
            val value = node[field]
            if (value != null && value != JsonNull && value != JsonArray(emptyList()) && value != JsonObject(emptyMap()))
                fail(field, "Feature is not supported in P1")
        }
        fun number(field: String, default: Int = 0): BigDecimal =
            node.num(field) ?: default.toBigDecimal().also { if (node[field] != null) fail(field, "Expected decimal number") }
        fun dimension(key: String): BigDecimal? {
            val dimension = (node["absoluteBoundingBox"] as? JsonObject)?.num(key)
            if (dimension == null || dimension <= BigDecimal.ZERO) fail("absoluteBoundingBox.$key", "Fixed sizing requires a positive dimension")
            return dimension
        }
        fun putSizing(dst: JsonObjectBuilder, horizontal: String?, vertical: String?) {
            when (horizontal) {
                "FIXED" -> dimension("width")?.let { dst.put("width", it.normalized()) }
                "FILL" -> if (parent == "VERTICAL") dst.put("fillWidth", true)
                          else fail("layoutSizingHorizontal", "Horizontal FILL requires a vertical parent in P1")
                "HUG" -> Unit
                else -> fail("layoutSizingHorizontal", "Unsupported or indeterminate horizontal sizing")
            }
            when (vertical) {
                "FIXED" -> dimension("height")?.let { dst.put("height", it.normalized()) }
                "HUG" -> Unit
                else -> fail("layoutSizingVertical", "Unsupported or indeterminate vertical sizing")
            }
        }

        if (node["visible"] != null && node.flag("visible") == null) fail("visible", "Expected boolean")
        if (node["opacity"] != null && node.num("opacity")?.compareTo(BigDecimal.ONE) != 0)
            fail("opacity", "Layer opacity other than 1 is not supported")
        val blend = node.str("blendMode")
        if (blend != null && blend !in setOf("NORMAL", "PASS_THROUGH")) fail("blendMode", "Unsupported layer blend mode")
        expect("layoutPositioning", setOf("AUTO"))
        zero("rotation")
        val transform = node["relativeTransform"]
        if (transform != null && transform != JsonNull) {
            val rows = transform as? JsonArray
            val matrix = rows?.map { (it as? JsonArray)?.map(JsonElement::decimal) }
            if (matrix == null || matrix.size != 2 || matrix.any { it == null || it.size != 3 || it.any { v -> v == null } } ||
                matrix[0]?.get(0)?.compareTo(BigDecimal.ONE) != 0 || matrix[0]?.get(1)?.signum() != 0 ||
                matrix[1]?.get(0)?.signum() != 0 || matrix[1]?.get(1)?.compareTo(BigDecimal.ONE) != 0) {
                fail("relativeTransform", "Only translation is supported; rotation, scale, and shear are unsupported")
            }
        }
        if (node["isMask"] != null && node.flag("isMask") != false) fail("isMask", "Masks are not supported")
        for (field in listOf("boundVariables", "minWidth", "maxWidth", "minHeight", "maxHeight", "layoutGrids")) absent(field)
        for (field in listOf("effects", "strokes")) {
            val raw = node[field] ?: continue
            if (raw !is JsonArray || raw.any { (it as? JsonObject)?.flag("visible") != false }) fail(field, "Visible $field are not supported")
        }

        if (asset != null && asset.nodeId == id) {
            if (assetUsed) fail("id", "Mapped node appears more than once")
            assetUsed = true
            if (root || node.str("type") != "VECTOR") {
                fail("type", "Only an explicitly mapped non-root VECTOR leaf is supported")
                return null
            }
            absent("children")
            absent("interactions"); absent("reactions")
            expect("layoutMode", setOf("NONE"))
            expect("layoutAlign", setOf("INHERIT"))
            zero("layoutGrow")
            for (axis in listOf("layoutSizingHorizontal", "layoutSizingVertical"))
                if (node.str(axis) != "FIXED") fail(axis, "Mapped bitmap leaf requires explicit FIXED dimension")
            val width = dimension("width")
            val height = dimension("height")
            if (width?.compareTo(asset.width.toBigDecimal()) != 0 || height?.compareTo(asset.height.toBigDecimal()) != 0)
                fail("absoluteBoundingBox", "Mapped bitmap pixel dimensions must exactly match node logical dp bounds")
            return buildJsonObject {
                put("id", id)
                put("type", "DecorativeImage")
                put("resourceName", asset.resourceName)
                width?.let { put("width", it.normalized()) }
                height?.let { put("height", it.normalized()) }
            }
        }

        return when (val type = node.str("type")) {
            "FRAME", "INSTANCE" -> {
                val componentId = if (type == "INSTANCE") {
                    if (!flattenInstances) {
                        fail("type", "Unsupported Figma node type: INSTANCE (opt in with --flatten-instances for expanded visual-only instances)")
                        return null
                    }
                    val origin = node.str("componentId")
                    if (origin == null || origin.length > 64 || !figmaComponentId.matches(origin))
                        fail("componentId", "Expected canonical component ID for expanded instance")
                    origin
                } else null
                val layout = node.str("layoutMode")
                if (layout !in setOf("VERTICAL", "HORIZONTAL")) {
                    fail("layoutMode", "Only horizontal or vertical Auto Layout frames are supported")
                    return null
                }
                expect("layoutWrap", setOf("NO_WRAP"))
                expect("primaryAxisAlignItems", setOf("MIN", "CENTER", "SPACE_BETWEEN"))
                val distribute = node.str("primaryAxisAlignItems") == "SPACE_BETWEEN"
                if (distribute) {
                    zero("itemSpacing")
                    expect("counterAxisAlignContent", setOf("AUTO"))
                }
                expect("counterAxisAlignItems", setOf("MIN", "CENTER"))
                if (node["itemReverseZIndex"] != null && node.flag("itemReverseZIndex") != false)
                    fail("itemReverseZIndex", "Reverse stacking is not supported")
                if (node["clipsContent"] != null && node.flag("clipsContent") == null) fail("clipsContent", "Expected boolean")
                zero("cornerSmoothing")
                val scalar = node.num("cornerRadius")
                val limit = 2048.toBigDecimal()
                if (node["cornerRadius"] != null && (scalar == null || scalar.signum() < 0 || scalar > limit))
                    fail("cornerRadius", "Expected circular radius in 0..2048")
                val rawCorners = node["rectangleCornerRadii"]
                val parsedCorners = (rawCorners as? JsonArray)?.map { it.decimal() }
                val corners = if (rawCorners != null) {
                    if (parsedCorners == null || parsedCorners.size != 4 || parsedCorners.any { it == null || it.signum() < 0 || it > limit }) {
                        fail("rectangleCornerRadii", "Expected four circular radii in 0..2048, clockwise from top left")
                        null
                    } else parsedCorners.filterNotNull().also { values ->
                        if (scalar != null && values.any { it.compareTo(scalar) != 0 })
                            fail("rectangleCornerRadii", "Uniform and per-corner radius values conflict")
                    }
                } else scalar?.let { List(4) { _ -> it } }
                val paints = node["fills"] as? JsonArray
                val singlePaint = paints?.filter { (it as? JsonObject)?.flag("visible") != false }?.singleOrNull() as? JsonObject
                val gradient = if (singlePaint?.str("type") == "GRADIENT_LINEAR")
                    parseLinearGradient(singlePaint, id, "$path.fills") else null
                val bg = if (singlePaint?.str("type") == "GRADIENT_LINEAR") null
                    else parseFill(node["fills"], id, "$path.fills", required = false)
                if (node["fills"] == null && node["background"] != null) fail("background", "Legacy background must be represented as fills")
                val padding = listOf("paddingLeft", "paddingTop", "paddingRight", "paddingBottom").map { number(it) }
                val children = node["children"] as? JsonArray
                if (children == null || children.size > 150) fail("children", "Expected complete children array of at most 150 entries")
                if (type == "INSTANCE" && children.isNullOrEmpty())
                    fail("children", "Expanded visual instance must include a non-empty children array; collapsed instances are not supported")
                if (type == "INSTANCE") {
                    // Do not silently turn a prototype trigger into an apparently complete component.
                    absent("interactions"); absent("reactions")
                }
                fun legacy(field: String): String? = when (node.str(field)) {
                    "AUTO" -> "HUG"; "FIXED" -> "FIXED"; null -> null; else -> { fail(field, "Unsupported axis sizing"); null }
                }
                val h = node.str("layoutSizingHorizontal") ?: when {
                    !root && parent == "VERTICAL" && node.str("layoutAlign") == "STRETCH" -> "FILL"
                    !root && parent == "HORIZONTAL" && node.num("layoutGrow")?.signum() == 1 -> "FILL"
                    else -> legacy(if (layout == "HORIZONTAL") "primaryAxisSizingMode" else "counterAxisSizingMode")
                }
                val v = node.str("layoutSizingVertical") ?: when {
                    !root && parent == "HORIZONTAL" && node.str("layoutAlign") == "STRETCH" -> "FILL"
                    !root && parent == "VERTICAL" && node.num("layoutGrow")?.signum() == 1 -> "FILL"
                    else -> legacy(if (layout == "HORIZONTAL") "counterAxisSizingMode" else "primaryAxisSizingMode")
                }
                if (distribute) {
                    val row = layout == "HORIZONTAL"
                    val axis = if (row) "width" else "height"
                    val sizeKey = if (row) "layoutSizingHorizontal" else "layoutSizingVertical"
                    val childFrames = children.orEmpty().mapNotNull { it as? JsonObject }.filter { it.flag("visible") != false }
                    val mainSizes = childFrames.mapIndexed { index, child ->
                        if (child.str(sizeKey) != "FIXED" || (child["layoutGrow"] != null && child.num("layoutGrow")?.signum() != 0)) {
                            fail("primaryAxisAlignItems", "SPACE_BETWEEN visible child ${child.str("id") ?: index} requires explicit FIXED main-axis sizing and zero layoutGrow")
                        }
                        (child["absoluteBoundingBox"] as? JsonObject)?.num(axis)?.takeIf { it.signum() > 0 }
                    }
                    val declared = dimension(axis)
                    if (mainSizes.all { it != null } && declared != null) {
                        val used = mainSizes.filterNotNull().fold(BigDecimal.ZERO, BigDecimal::add) +
                            (if (row) padding[0] + padding[2] else padding[1] + padding[3])
                        if (used > declared) fail("primaryAxisAlignItems", "SPACE_BETWEEN children and main-axis padding exceed the design extent")
                    }
                }
                buildJsonObject {
                    put("id", id)
                    componentId?.let { put("sourceComponentId", it) }
                    put("type", if (layout == "VERTICAL") "Column" else "Row")
                    if (root && fitWidth) {
                        if (h != "FIXED" || layout != "VERTICAL") fail("layoutSizingHorizontal",
                            "Responsive root width requires a VERTICAL frame with explicit FIXED width")
                        // Validate original design width even when the caller requests host sizing.
                        dimension("width")
                        putSizing(this, "HUG", v)
                        put("fillWidth", true)
                    } else putSizing(this, h, v)
                    if (distribute) put("mainSpaceBetween", true) else put("gap", number("itemSpacing").normalized())
                    if (node.str("primaryAxisAlignItems") == "CENTER") put("mainCentered", true)
                    if (node.str("counterAxisAlignItems") == "CENTER") put("crossCentered", true)
                    if (padding.any { it.signum() != 0 }) put("paddingEdges", JsonArray(padding.map { JsonPrimitive(it.normalized()) }))
                    if (node.flag("clipsContent") == true) put("clip", true)
                    bg?.let { put("background", it) }
                    if (corners?.any { it.signum() > 0 } == true)
                        put("cornerRadii", JsonArray(corners.map { JsonPrimitive(it.normalized()) }))
                    gradient?.let { put("linearGradient", it) }
                    putJsonArray("children") {
                        children?.take(150)?.forEachIndexed { index, child ->
                            if (visited >= 300) fail("children", "Frame exceeds 300 nodes")
                            else if (child is JsonObject) convert(child, "$path.children[$index]", layout, depth + 1)?.let { add(it) }
                            else fail("children[$index]", "Expected child node object")
                        }
                    }
                }
            }
            "TEXT" -> {
                val style = node["style"] as? JsonObject
                if (style == null) { fail("style", "Expected Figma text style object"); return null }
                val chars = node.str("characters")
                if (chars == null) fail("characters", "Expected textual characters")
                val overrides = node["characterStyleOverrides"]
                if (overrides != null && (overrides !is JsonArray || overrides.any { item -> item.decimal()?.signum() != 0 }))
                    fail("characterStyleOverrides", "Per-character style is not supported")
                absent("styleOverrideTable")
                absent("listSpacing")
                for (field in listOf("paragraphIndent", "paragraphSpacing"))
                    if (style[field] != null && style.num(field)?.signum() != 0) fail("style.$field", "Paragraph styling is not supported")
                if (style.flag("italic") == true) fail("style.italic", "Italic text is not supported")
                if (style.str("textCase") !in setOf(null, "ORIGINAL")) fail("style.textCase", "Text case transformations are not supported")
                if (style.str("textDecoration") !in setOf(null, "NONE")) fail("style.textDecoration", "Text decoration is not supported")
                if (style.str("textAlignHorizontal") !in setOf(null, "LEFT")) fail("style.textAlignHorizontal", "Only LEFT text alignment is supported")
                if (style.str("textAlignVertical") !in setOf(null, "TOP")) fail("style.textAlignVertical", "Only TOP text alignment is supported")
                if (style.str("textTruncation") !in setOf(null, "DISABLED")) fail("style.textTruncation", "Text truncation is not supported")
                if (style["hyperlink"] != null && style["hyperlink"] != JsonNull) fail("style.hyperlink", "Hyperlink binding is not supported")
                if ((style["openTypeFlags"] as? JsonObject)?.isNotEmpty() == true) fail("style.openTypeFlags", "OpenType feature overrides are not supported")
                if ((style["boundVariables"] as? JsonObject)?.isNotEmpty() == true) fail("style.boundVariables", "Variable-bound font style is not supported")
                val resize = style.str("textAutoResize")
                if (resize !in setOf(null, "NONE", "HEIGHT", "WIDTH_AND_HEIGHT")) fail("style.textAutoResize", "Unsupported text resize mode")
                val weight = style.num("fontWeight")
                if (style["fontWeight"] != null && weight?.stripTrailingZeros()?.toPlainString() !in setOf("400", "700"))
                    fail("style.fontWeight", "Only regular (400) and bold (700) are supported")
                val family = when (style.str("fontFamily")?.lowercase()) {
                    "sans-serif" -> "SansSerif"; "serif" -> "Serif"; "monospace" -> "Monospace"
                    else -> { fail("style.fontFamily", "Named font requires an explicit font asset/mapping; P1 only accepts generic sans-serif/serif/monospace"); null }
                }
                val size = style.num("fontSize")
                if (size == null) fail("style.fontSize", "Expected numeric fontSize")
                val fill = parseFill(node["fills"] ?: style["fills"], id, "$path.fills", required = true)
                val lineHeight = when (style.str("lineHeightUnit")) {
                    "PIXELS", "FONT_SIZE_%" -> style.num("lineHeightPx").also {
                        if (it == null) fail("style.lineHeightPx", "Expected computed line height")
                    }
                    null, "INTRINSIC_%" -> null.also {
                        if (style.num("lineHeightPercent")?.compareTo(100.toBigDecimal())?.let { different -> different != 0 } == true)
                            fail("style.lineHeightPercent", "Non-default intrinsic line height is not supported")
                    }
                    else -> null.also { fail("style.lineHeightUnit", "Unsupported line-height unit") }
                }
                val h = node.str("layoutSizingHorizontal") ?: when {
                    parent == "VERTICAL" && node.str("layoutAlign") == "STRETCH" -> "FILL"
                    parent == "HORIZONTAL" && node.num("layoutGrow")?.signum() == 1 -> "FILL"
                    resize == "WIDTH_AND_HEIGHT" -> "HUG"
                    else -> "FIXED"
                }
                val v = node.str("layoutSizingVertical") ?: when {
                    parent == "HORIZONTAL" && node.str("layoutAlign") == "STRETCH" -> "FILL"
                    parent == "VERTICAL" && node.num("layoutGrow")?.signum() == 1 -> "FILL"
                    resize in setOf("WIDTH_AND_HEIGHT", "HEIGHT") -> "HUG"
                    else -> "FIXED"
                }
                buildJsonObject {
                    put("id", id)
                    put("type", "Text")
                    putSizing(this, h, v)
                    chars?.let { put("text", it) }
                    size?.let { put("fontSize", it.normalized()) }
                    family?.let { put("fontFamily", it) }
                    if (weight?.compareTo(700.toBigDecimal()) == 0) put("bold", true)
                    lineHeight?.let { put("lineHeight", it.normalized()) }
                    style.num("letterSpacing")?.let { if (it.signum() != 0) put("letterSpacing", it.normalized()) }
                    fill?.let { put("color", it) }
                }
            }
            else -> { fail("type", "Unsupported Figma node type: ${type ?: "(missing)"}"); null }
        }
    }

    private fun parseLinearGradient(paint: JsonObject, id: String, path: String): JsonObject? {
        fun reject(reason: String): Nothing? { problem(id, path, reason); return null }
        if ((paint["blendMode"] != null && paint.str("blendMode") != "NORMAL") ||
            (paint["boundVariables"] != null && paint["boundVariables"] != JsonObject(emptyMap())))
            return reject("Linear gradient requires NORMAL blend and no variable bindings")
        val handles = (paint["gradientHandlePositions"] as? JsonArray)?.map { point ->
            val obj = point as? JsonObject
            val x = obj?.num("x")
            val y = obj?.num("y")
            if (x == null || y == null || x !in BigDecimal.ZERO..BigDecimal.ONE || y !in BigDecimal.ZERO..BigDecimal.ONE)
                null else x.normalized() to y.normalized()
        }
        if (handles == null || handles.size != 3 || handles.any { it == null })
            return reject("Gradient requires three normalized handles in [0,1]")
        val (start, end, across) = handles.filterNotNull()
        fun same(a: BigDecimal, b: BigDecimal) = a.compareTo(b) == 0
        val horizontal = same(start.second, end.second) && same(start.first, across.first) &&
            !same(start.first, end.first) && !same(start.second, across.second)
        val vertical = same(start.first, end.first) && same(start.second, across.second) &&
            !same(start.second, end.second) && !same(start.first, across.first)
        if (!horizontal && !vertical)
            return reject("Only axis-aligned, nondegenerate linear gradients with perpendicular third handle are supported")
        val opacity = paint.num("opacity") ?: (if (paint["opacity"] == null) BigDecimal.ONE else null)
        if (opacity == null || opacity !in BigDecimal.ZERO..BigDecimal.ONE)
            return reject("Gradient opacity must be in 0..1")
        val stops = paint["gradientStops"] as? JsonArray
        if (stops == null || stops.size !in 2..16)
            return reject("Linear gradient requires 2 to 16 strictly ordered color stops")
        var previous: BigDecimal? = null
        val result = mutableListOf<JsonObject>()
        for (stop in stops) {
            val item = stop as? JsonObject
            val position = item?.num("position")?.normalized()
            val color = item?.get("color") as? JsonObject
            val argb = color?.let { rgba(it, opacity) }
            val previousPosition = previous
            if (position == null || position !in BigDecimal.ZERO..BigDecimal.ONE ||
                (previousPosition != null && position <= previousPosition) || argb == null)
                return reject("Gradient stops must be strictly ascending in 0..1 with valid RGBA colors")
            result += buildJsonObject { put("position", position); put("color", argb) }
            previous = position
        }
        return buildJsonObject {
            put("axis", if (horizontal) "x" else "y")
            put("start", if (horizontal) start.first else start.second)
            put("end", if (horizontal) end.first else end.second)
            put("stops", JsonArray(result))
        }
    }

    private fun rgba(color: JsonObject, opacity: BigDecimal): String? {
        val channels = listOf("a", "r", "g", "b").map { color.num(it) ?: return null }
        if (channels.any { it !in BigDecimal.ZERO..BigDecimal.ONE }) return null
        fun hex(channel: BigDecimal): String = channel.multiply(255.toBigDecimal())
            .setScale(0, RoundingMode.HALF_UP).toInt().toString(16).padStart(2, '0').uppercase()
        return "#" + hex(channels[0] * opacity) + channels.drop(1).joinToString("") { hex(it) }
    }

    private fun parseFill(raw: JsonElement?, id: String, path: String, required: Boolean): String? {
        val paints = raw as? JsonArray
        if (raw != null && paints == null) { problem(id, path, "Expected paint array"); return null }
        val visible = paints.orEmpty().filter { (it as? JsonObject)?.flag("visible") != false }
        if (visible.isEmpty()) {
            if (required) problem(id, path, "Text requires one visible solid fill")
            return null
        }
        val paint = visible.singleOrNull() as? JsonObject
        val color = paint?.get("color") as? JsonObject
        if (paint?.str("type") != "SOLID" || color == null || paint.str("blendMode") !in setOf(null, "NORMAL") ||
            (paint["boundVariables"] as? JsonObject)?.isNotEmpty() == true) {
            problem(id, path, "Only one visible, unbound SOLID paint with normal blending is supported")
            return null
        }
        val one = BigDecimal.ONE
        val zero = BigDecimal.ZERO
        val opacity = paint.num("opacity") ?: if (paint["opacity"] == null) one else null
        val r = color.num("r")
        val g = color.num("g")
        val b = color.num("b")
        val a = color.num("a")
        val values = listOf(opacity, a, r, g, b)
        if (values.any { it == null || it < zero || it > one }) {
            problem(id, path, "Color RGBA and optional paint opacity must be in 0..1")
            return null
        }
        fun hex(channel: BigDecimal): String = channel.multiply(255.toBigDecimal())
            .setScale(0, RoundingMode.HALF_UP).toInt().toString(16).padStart(2, '0').uppercase()
        return "#${hex(a!! * opacity!!)}${hex(r!!)}${hex(g!!)}${hex(b!!)}"
    }
}

private fun JsonObject.str(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonObject.flag(name: String): Boolean? = when (this[name]) {
    JsonPrimitive(true) -> true
    JsonPrimitive(false) -> false
    else -> null
}
private fun JsonObject.num(name: String): BigDecimal? = this[name]?.decimal()
private fun JsonElement.decimal(): BigDecimal? = (this as? JsonPrimitive)?.takeUnless { it.isString }
    ?.content?.takeIf { it.length <= 32 }?.toBigDecimalOrNull()?.takeIf { it.scale() in -32..32 }
// The P1 IR contract retains six decimals and the generated Float dimensions use this exact decimal value.
private fun BigDecimal.normalized(): BigDecimal = setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().let {
    if (it.scale() < 0) it.setScale(0) else it
}
