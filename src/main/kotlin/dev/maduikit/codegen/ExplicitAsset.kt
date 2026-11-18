package dev.maduikit.codegen

import kotlinx.serialization.json.*
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.zip.CRC32
import javax.imageio.ImageIO

// One canonical expanded owner, one child, and optionally one explicitly validated wrapper hop.
internal val decorativeInstanceChildId = Regex("I([0-9]+:[0-9]+);([0-9]+:[0-9]+)(?:;([0-9]+:[0-9]+))?")

internal data class ExplicitAsset(
    val nodeId: String,
    val resourceName: String,
    val width: Int,
    val height: Int,
    val bytes: ByteArray,
    val hash: String,
    val mapHash: String,
) {
    val route = decorativeInstanceChildId.matchEntire(nodeId)
    val expandedOwnerId get() = route?.groupValues?.get(1)
    val inertWrapperId get() = route?.let { if (it.groupValues[3].isEmpty()) null else "I${it.groupValues[1]};${it.groupValues[2]}" }
    val outputPath get() = "composeResources/drawable/$resourceName.png"
}

// Securely restrict input to a small regular file; do not accept a final symlink or follow a URL.
private fun localBytes(path: Path, limit: Int): ByteArray {
    require(Files.isRegularFile(path, NOFOLLOW_LINKS)) { "Expected a non-symlink regular local file" }
    return Files.newInputStream(path, NOFOLLOW_LINKS).use { it.readNBytes(limit + 1) }.also {
        require(it.size <= limit) { "Local file exceeds the byte limit" }
    }
}

internal fun readExplicitAsset(path: Path): ExplicitAsset {
    val mapPath = path.toAbsolutePath().normalize()
    val mapBytes = localBytes(mapPath, 4096)
    val map = Json.parseToJsonElement(UTF_8.newDecoder().decode(ByteBuffer.wrap(mapBytes)).toString()) as? JsonObject
        ?: throw IllegalArgumentException("Asset map must be a JSON object")
    require(map.keys == setOf("schemaVersion", "nodeId", "file", "sha256") && map["schemaVersion"] == JsonPrimitive(1)) {
        "Asset map requires only schemaVersion=1, nodeId, file and sha256"
    }
    fun string(key: String): String? = (map[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val nodeId = string("nodeId")
    require(nodeId != null && nodeId.length <= 64 && (figmaComponentId.matches(nodeId) ||
        decorativeInstanceChildId.matches(nodeId))) {
        "Asset map requires a canonical Figma node ID or a direct or single-wrapper expanded instance child ID"
    }
    val file = string("file")
    require(file != null && Regex("mad_icon_[a-z][a-z0-9_]{0,38}\\.png").matches(file)) {
        "Resource file must be one lowercase mad_icon_*.png basename beside its map"
    }
    val declaredHash = string("sha256")
    require(declaredHash != null && Regex("[a-f0-9]{64}").matches(declaredHash)) {
        "Asset map requires a lowercase SHA-256 digest"
    }
    val payload = localBytes(mapPath.resolveSibling(file), 32 * 1024)
    val hash = sha256(payload)
    require(hash == declaredHash) { "Resource PNG bytes do not match the asset map SHA-256" }
    val (width, height) = inspectPng(payload)
    return ExplicitAsset(nodeId, file.removeSuffix(".png"), width, height, payload, hash, sha256(mapBytes))
}

// Narrow input profile: one small RGBA PNG, no ancillary chunks (e.g. profiles, APNG,
// text metadata, density overrides), no appended data, and a real partial-alpha silhouette.
private fun inspectPng(bytes: ByteArray): Pair<Int, Int> {
    fun png(condition: Boolean, reason: String) = require(condition) { reason }
    val signature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
    png(bytes.size >= 57 && bytes.copyOfRange(0, 8).contentEquals(signature), "Expected a PNG signature")
    val chunks = ByteBuffer.wrap(bytes).position(8)
    var width = 0
    var height = 0
    var foundData = false
    var finished = false
    while (chunks.remaining() >= 12 && !finished) {
        val length = chunks.int
        png(length >= 0 && length <= chunks.remaining() - 8, "Malformed PNG chunk length")
        val start = chunks.position()
        val name = chunks.int
        val at = chunks.position()
        val crc = CRC32().apply { update(bytes, start, length + 4) }.value
        png((chunks.getInt(at + length).toLong() and 0xffffffff) == crc, "PNG chunk CRC failed")
        when (name) {
            0x49484452 -> { // IHDR
                png(start == 12 && length == 13, "PNG needs exactly one initial IHDR")
                width = chunks.int
                height = chunks.int
                png(width in 1..64 && height in 1..64, "PNG dimensions must be 1..64 pixels")
                png(chunks.get() == 8.toByte() && chunks.get() == 6.toByte() &&
                    chunks.get() == 0.toByte() && chunks.get() == 0.toByte() && chunks.get() == 0.toByte(),
                    "PNG must be 8-bit RGBA, non-interlaced")
            }
            0x49444154 -> { // IDAT
                png(width > 0, "PNG image data must follow IHDR")
                foundData = true
            }
            0x49454e44 -> { // IEND
                png(foundData && length == 0, "PNG image data or terminal chunk missing")
                finished = true
            }
            else -> throw IllegalArgumentException("PNG ancillary or unknown chunks are unsupported")
        }
        chunks.position(at + length + 4)
    }
    png(finished && !chunks.hasRemaining(), "PNG missing a clean terminal chunk")
    val bitmap = ImageIO.read(ByteArrayInputStream(bytes))
        ?: throw IllegalArgumentException("PNG pixels could not be decoded")
    png(bitmap.width == width && bitmap.height == height && bitmap.colorModel.hasAlpha(), "PNG dimensions/alpha misread")
    val opacities = bitmap.getRGB(0, 0, width, height, null, 0, width).map { it ushr 24 }
    png(opacities.any { it == 0 } && opacities.any { it != 0 }, "Decorative PNG must include transparent and painted pixels")
    return width to height
}
