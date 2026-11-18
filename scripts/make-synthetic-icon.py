#!/usr/bin/env python3
"""Rebuild the public, hand-authored 20-pixel icon from an integer grid."""
import hashlib
import json
from pathlib import Path
import struct
import zlib

dst = Path(__file__).resolve().parents[1] / "fixtures/figma/resource"
dst.mkdir(parents=True, exist_ok=True)
name = "mad_icon_synthetic_diamond.png"

def chunk(tag, data):
    return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data))

def pixel(x, y):
    d = abs(2*x-19) + abs(2*y-19)
    if d <= 8:
        return bytes.fromhex("f6be4fff")
    if d <= 16:
        return bytes.fromhex("2c669aff")
    return bytes(4)

rows = b"".join(bytes(1) + b"".join(pixel(x, y) for x in range(20)) for y in range(20))
out = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 20, 20, 8, 6, 0, 0, 0))
# zlib wrapper with one uncompressed DEFLATE block. Stable without relying on compressor version.
deflate = b"\x78\x01\x01" + struct.pack("<HH", len(rows), 0xffff ^ len(rows)) + rows + struct.pack(">I", zlib.adler32(rows))
out += chunk(b"IDAT", deflate) + chunk(b"IEND", b"")
(dst/name).write_bytes(out)
(dst/"asset-map.json").write_text(json.dumps({"schemaVersion":1, "nodeId":"30:3", "file":name,
    "sha256":hashlib.sha256(out).hexdigest()},indent=2) + "\n",encoding="utf-8")
