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


def font_contract(path):
    require(not path.is_symlink(), "Linked font map")
    resolved = path.resolve(strict=True)
    require(resolved.stat().st_size in range(1, 4097), "Invalid font map size")
    require("\\" not in resolved.read_text(encoding="utf-8"), "Escaped mapping keys or values are not supported")
    value = read_json(resolved)
    require(set(value) == {"schemaVersion", "mappings"} and type(value["schemaVersion"]) is int and value["schemaVersion"] == 1, "Invalid font map schema")
    mappings = value["mappings"]
    require(isinstance(mappings, list) and len(mappings) == 1, "Exactly one font binding required")
    binding = mappings[0]
    require(isinstance(binding, dict) and set(binding) == {"family", "role"}, "Invalid font binding")
    family, role = binding["family"], binding["role"]
    require(isinstance(family, str) and 1 <= len(family) <= 100 and family == family.strip()
            and re.fullmatch(r"[A-Za-z0-9 _-]+", family), "Invalid font family")
    require(family.lower() not in {"sans-serif", "serif", "monospace"}, "Generic mapping forbidden")
    require(isinstance(role, str) and role in {"body", "title", "label"}, "Unknown font role")
    return resolved, digest(resolved), dict(version=1, family=family, role=role, parameter="fontRole" + role.title())


def verify(stage, input_hash, node, fit_width, flatten, map_hash, font_hash=None, font_binding=None, component_hash=None, component_binding=None):
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
    require(manifest.get("fontMapSha256") == font_hash, "Font map hash mismatch")
    require(manifest.get("fontRoleBinding") == font_binding, "Font role contract mismatch")
    if font_hash is None:
        require("fontRoleBinding" not in manifest and "fontMapSha256" not in manifest, "Unexpected font contract")
    else:
        require(node and isinstance(font_binding, dict), "Font binding requires Figma input")
        require(type(manifest["fontRoleBinding"].get("version")) is int, "Invalid font binding version")
    require(manifest.get("componentMapSha256") == component_hash and manifest.get("componentBinding") == component_binding, "Component binding receipt mismatch")
    require(manifest.get("sdkRequired") is (component_binding is not None), "SDK requirement mismatch")
    expected_signature = "selected: Boolean, onSelect: () -> Unit" if component_binding else ""
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
        # Confine this check to the generated bundle. Trusted host ancestors may
        # legitimately be aliases, e.g. /var -> /private/var on macOS.
        cursor = path
        while True:
            require(not cursor.is_symlink(), "Linked generated path")
            if cursor == stage:
                break
            cursor = cursor.parent
        require(path.is_file() and isinstance(sha, str) and re.fullmatch(r"[0-9a-f]{64}", sha), "Invalid file receipt")
        require(digest(path) == sha, f"Generated file hash mismatch: {name}")
    if map_hash:
        require(files[asset["file"]] == asset.get("sha256"), "Asset receipt mismatch")
    source_map = read_json(stage / "source-map.json")
    require(source_map.get("file") == screen, "Source map file mismatch")
    require(source_map.get("componentBinding") == component_binding, "Source component trace mismatch")
    # Metadata is not hashed by the generator; it is checked for consistency, not authenticated.
    allowed = expected | {"manifest.json", "diagnostics.json", "source-map.json"}
    if node:
        normalized = read_json(stage / "normalized-frame.json")
        def roles(value):
            if isinstance(value, dict):
                if "fontRole" in value:
                    require(value.get("type") == "Text" and "fontFamily" not in value, "Invalid normalized font role")
                    yield value["fontRole"]
                for child in value.values():
                    yield from roles(child)
            elif isinstance(value, list):
                for child in value:
                    yield from roles(child)
        bound = list(roles(normalized))
        code = (stage / screen).read_text(encoding="utf-8")
        signatures = re.findall(r"^fun FigmaProfileScreen\(([^\n]*)\) \{$", code, re.MULTILINE)
        frame = normalized["frame"]
        roles_present = []
        def component_roles(v):
            if isinstance(v, dict):
                if "componentRole" in v: roles_present.append((v.get("id"), v["componentRole"]))
                for child in v.values(): component_roles(child)
            elif isinstance(v, list):
                for child in v: component_roles(child)
        component_roles(frame)
        if component_binding:
            require(roles_present == [(node,"filterTabs"),(component_binding["instanceId"],"filterTab")], "Component normalized role mismatch")
            require(frame["children"][0].get("enabled") is component_binding["enabled"], "Component enabled mismatch")
            enabled = str(component_binding["enabled"]).lower()
            require(code.count("MadFilterTab(selected = selected, onSelect = onSelect, enabled = " + enabled + ") {") == 1, "Controlled component call mismatch")
        else:
            require(not roles_present, "Missing component map")
        if font_binding:
            require(bound and set(bound) == {font_binding["role"]}, "Normalized font role mismatch")
            parameter = font_binding["parameter"]
            require(signatures == [(expected_signature + ", " if expected_signature else "") + f"{parameter}: FontFamily"], "Missing required font parameter")
            # Ignore text literal contents when counting generated callsite arguments.
            calls = re.findall(r'^\s*Text\(text = "(?:\\.|[^"\\])*",(.*)$', code, re.MULTILINE)
            count = sum(len(re.findall(r"\bfontFamily = " + parameter + r"(?=,|\))", call)) for call in calls)
            require(count == len(bound), "Font callsite binding mismatch")
        else:
            require(not bound and signatures == [expected_signature], "Missing font map for generated font binding")
        allowed.add("normalized-frame.json")
    actual = {p.relative_to(stage).as_posix() for p in stage.rglob("*") if not p.is_dir() or p.is_symlink()}
    require(actual == allowed, "Unexpected output contents")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--launcher")
    parser.add_argument("--verify-only", action="store_true", help="Verify existing --out immediately before consumer compilation; never mutate bundle")
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--node")
    parser.add_argument("--fit-width", action="store_true")
    parser.add_argument("--flatten-instances", action="store_true")
    parser.add_argument("--asset-map", type=Path)
    parser.add_argument("--font-map", type=Path)
    parser.add_argument("--component-map", type=Path)
    args = parser.parse_args()
    stage = None
    try:
        require(args.node or not (args.fit_width or args.flatten_instances or args.asset_map or args.font_map or args.component_map), "Figma flags require --node")
        output = args.out.absolute()
        require(args.verify_only or not os.path.lexists(output), "Output already exists; choose a new directory")
        require(output.parent.is_dir(), "Output parent must already exist")
        input_hash = digest(args.input)
        map_hash = digest(args.asset_map) if args.asset_map else None
        font_path, font_hash, font_binding = font_contract(args.font_map) if args.font_map else (None, None, None)
        component_path = args.component_map.resolve(strict=True) if args.component_map else None
        component_hash = digest(component_path) if component_path else None
        component_binding = None
        if component_path:
            from component_binding_contract import validate, read as read_component_json
            require(not args.component_map.is_symlink() and args.flatten_instances and not args.fit_width and not args.asset_map, "Component map requires fixed expanded layout")
            component_binding = dict(version=1, **validate(read_component_json(args.input), read_component_json(component_path, sidecar=True), args.node))
        if args.verify_only:
            require(output.is_dir() and not output.is_symlink(), "Missing or linked bundle")
            verify(output, input_hash, args.node, args.fit_width, args.flatten_instances, map_hash, font_hash, font_binding, component_hash, component_binding)
            require(digest(args.input) == input_hash, "Input changed during verification")
            require(not args.asset_map or digest(args.asset_map) == map_hash, "Asset map changed during verification")
            if args.font_map:
                require(font_contract(args.font_map) == (font_path, font_hash, font_binding), "Font map changed during verification")
            require(not component_path or (not args.component_map.is_symlink() and args.component_map.resolve(strict=True) == component_path and digest(component_path) == component_hash), "Component map changed during verification")
            print("Verified existing bundle; consumer may compile immediately without changing inputs")
            return 0
        require(args.launcher, "Generation requires --launcher")
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
        if font_path:
            command += ["--font-map", str(font_path)]
        if component_path:
            command += ["--component-map", str(component_path)]
        result = subprocess.run(command, check=False)
        require(result.returncode == 0, f"Generator exited {result.returncode}; nothing published")
        require(digest(args.input) == input_hash, "Input changed during generation")
        require(not args.asset_map or digest(args.asset_map) == map_hash, "Asset map changed during generation")
        if args.font_map:
            require(font_contract(args.font_map) == (font_path, font_hash, font_binding), "Font map changed during generation")
        verify(stage, input_hash, args.node, args.fit_width, args.flatten_instances, map_hash, font_hash, font_binding, component_hash, component_binding)
        require(not component_path or (args.component_map.resolve(strict=True) == component_path and digest(component_path) == component_hash), "Component map drift")
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
