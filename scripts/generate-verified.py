#!/usr/bin/env python3
"""Generate in isolation; publish only a verified, fresh output directory."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def require(condition, message):
    if not condition:
        raise ValueError(message)


def read_json(path):
    require(path.is_file() and not path.is_symlink(), f"Missing or linked metadata: {path.name}")
    def unique(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, f"Duplicate JSON key: {key}")
            result[key] = value
        return result
    value = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique)
    require(isinstance(value, dict), "Metadata must be an object")
    return value


def verify(stage, input_hash, node, fit_width, flatten, map_hash):
    manifest = read_json(stage / "manifest.json")
    diagnostics = read_json(stage / "diagnostics.json")
    require(manifest.get("schemaVersion") == 1 and manifest.get("target") == "cmp", "Unsupported manifest")
    require(diagnostics.get("schemaVersion") == 1 and diagnostics.get("ok") is True
            and diagnostics.get("errors") == [], "Generation diagnostics are not successful")
    require(manifest.get("inputSha256") == diagnostics.get("inputSha256") == input_hash, "Input hash mismatch")
    screen = "FigmaProfileScreen.generated.kt" if node else "SyntheticProfileScreen.generated.kt"
    require(manifest.get("inputFormat") == ("figma-rest-file-nodes" if node else None), "Input format mismatch")
    if node:
        require(manifest.get("frameId") == node, "Selected frame mismatch")
    require(manifest.get("rootWidthMode") == ("fit-host" if fit_width else None), "Width mode mismatch")
    require(manifest.get("instanceMode") == ("expanded-visual-v1" if flatten else None), "Instance mode mismatch")
    require(manifest.get("assetMapSha256") == map_hash, "Asset map hash mismatch")
    expected = {screen}
    if map_hash:
        asset = manifest.get("decorativeAsset")
        require(isinstance(asset, dict), "Missing asset receipt")
        name = asset.get("file")
        require(isinstance(name, str) and re.fullmatch(r"composeResources/drawable/mad_icon_[a-z0-9_]+\.png", name), "Unsafe asset path")
        expected.add(name)
    else:
        require("decorativeAsset" not in manifest, "Unexpected asset receipt")
    files = manifest.get("files")
    require(isinstance(files, dict) and set(files) == expected, "Unexpected or missing generated files")
    for name, sha in files.items():
        path = stage / name
        require(not any(p.is_symlink() for p in [path, *path.parents] if p != stage.parent), "Linked generated path")
        require(path.is_file() and isinstance(sha, str) and re.fullmatch(r"[0-9a-f]{64}", sha), "Invalid file receipt")
        require(digest(path) == sha, f"Generated file hash mismatch: {name}")
    if map_hash:
        require(files[asset["file"]] == asset.get("sha256"), "Asset receipt mismatch")
    source_map = read_json(stage / "source-map.json")
    require(source_map.get("file") == screen, "Source map file mismatch")
    # Metadata is not hashed by the generator; it is checked for consistency, not authenticated.
    allowed = expected | {"manifest.json", "diagnostics.json", "source-map.json"}
    if node:
        read_json(stage / "normalized-frame.json")
        allowed.add("normalized-frame.json")
    actual = {p.relative_to(stage).as_posix() for p in stage.rglob("*") if not p.is_dir() or p.is_symlink()}
    require(actual == allowed, "Unexpected output contents")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--launcher", required=True)
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--node")
    parser.add_argument("--fit-width", action="store_true")
    parser.add_argument("--flatten-instances", action="store_true")
    parser.add_argument("--asset-map", type=Path)
    args = parser.parse_args()
    stage = None
    try:
        require(args.node or not (args.fit_width or args.flatten_instances or args.asset_map), "Figma flags require --node")
        output = args.out.absolute()
        require(not os.path.lexists(output), "Output already exists; choose a new directory")
        require(output.parent.is_dir(), "Output parent must already exist")
        input_hash = digest(args.input)
        map_hash = digest(args.asset_map) if args.asset_map else None
        stage = Path(tempfile.mkdtemp(prefix=".mad-generation-", dir=output.parent))
        command = [str(Path(args.launcher).absolute()), "generate-figma" if args.node else "generate", "--input", str(args.input.absolute())]
        if args.node:
            command += ["--node", args.node]
        command += ["--out", str(stage)]
        if args.fit_width:
            command += ["--fit-width"]
        if args.flatten_instances:
            command += ["--flatten-instances"]
        if args.asset_map:
            command += ["--asset-map", str(args.asset_map.absolute())]
        result = subprocess.run(command, check=False)
        require(result.returncode == 0, f"Generator exited {result.returncode}; nothing published")
        require(digest(args.input) == input_hash, "Input changed during generation")
        require(not args.asset_map or digest(args.asset_map) == map_hash, "Asset map changed during generation")
        verify(stage, input_hash, args.node, args.fit_width, args.flatten_instances, map_hash)
        require(not os.path.lexists(output), "Output appeared during generation")
        stage.rename(output)
        stage = None
        print(f"Verified generation published to {output}")
        return 0
    except (OSError, ValueError, TypeError, KeyError) as error:
        print(f"Verified generation failed: {error}", file=sys.stderr)
        return 2
    finally:
        if stage is not None:
            shutil.rmtree(stage)


if __name__ == "__main__":
    sys.exit(main())
