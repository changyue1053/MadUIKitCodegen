#!/usr/bin/env python3
"""Deterministic public font-role contract tests; no network or installed fonts."""
from pathlib import Path
import hashlib
import json
import shutil
import subprocess
import sys
import tempfile

root = Path(__file__).resolve().parents[1]
launcher = Path(sys.argv[1]).resolve()
wrapper = root / "scripts/generate-verified.py"
source = root / "fixtures/figma/font-role.file-nodes.json"
map_file = root / "fixtures/figma/font-role/map.json"


def require(condition, label):
    if not condition:
        raise AssertionError(label)


with tempfile.TemporaryDirectory(prefix="mad-font-role-") as temporary:
    directory = Path(temporary)

    def generate(output, mapping=map_file, input_file=source, verified=False):
        if verified:
            command = [sys.executable, str(wrapper), "--launcher", str(launcher)]
        else:
            command = [str(launcher), "generate-figma"]
        command += ["--input", str(input_file), "--node", "10:1", "--out", str(output)]
        if mapping is not None:
            command += ["--font-map", str(mapping)]
        return subprocess.run(command, capture_output=True).returncode

    def verify(bundle, mapping=map_file):
        command = [sys.executable, str(wrapper), "--verify-only", "--input", str(source),
                   "--node", "10:1", "--out", str(bundle)]
        if mapping is not None:
            command += ["--font-map", str(mapping)]
        return subprocess.run(command, capture_output=True).returncode

    def contents(path):
        return {p.name: p.read_bytes() for p in path.iterdir()}

    first, second = directory / "first", directory / "second"
    require(generate(first, verified=True) == 0, "verified generation")
    require(generate(second, verified=True) == 0, "repeated generation")
    require(contents(first) == contents(second), "deterministic bundle")
    require(verify(first) == 0, "verify-before-compile")
    text = (first / "FigmaProfileScreen.generated.kt").read_text()
    for expected in ["fontRoleBody: FontFamily", "fontFamily = fontRoleBody", "fontSize = 24.sp",
                     "lineHeight = 32.sp", "FontWeight.Medium"]:
        require(expected in text, expected)

    # Kotlin reader and Python wrapper must reject the same restricted map vocabulary.
    base = json.loads(map_file.read_text())
    invalid = [
        '{"schemaVersion":1,"mappings":[{"family":"Synthetic Brand","role":"body","role":"body"}]}',
        '{"schemaVersion":1,"mappings":[{"family":"Synthetic Brand","role":"body","\\u0072ole":"body"}]}',
    ]
    for family, role in [("Other Brand", "body"), ("Synthetic Brand", "unknown"),
                         ("sans-serif", "body"), ("Synthetic Brand", None), ("", "body"),
                         (" Synthetic Brand", "body"), ("品牌", "body"), ("Brand\\Name", "body")]:
        invalid.append(json.dumps(dict(schemaVersion=1, mappings=[dict(family=family, role=role)]), ensure_ascii=False))
    invalid += [json.dumps(dict(schemaVersion=True, mappings=base["mappings"])),
                json.dumps(dict(schemaVersion=1, mappings=base["mappings"] * 2)),
                json.dumps(dict(schemaVersion=1, mappings=[])),
                json.dumps(dict(schemaVersion=1, mappings=[dict(family="Synthetic Brand", role="body", extra=1)]))]
    for index, raw in enumerate(invalid):
        mapping = directory / f"invalid-{index}.json"
        mapping.write_text(raw)
        for wrapped in (False, True):
            output = directory / f"invalid-{index}-{wrapped}"
            require(generate(output, mapping, verified=wrapped) == 2, f"invalid map {index}")
            require(not (output / "FigmaProfileScreen.generated.kt").exists(), "no rejected code")
    for role in ("title", "label"):
        mapping = directory / f"{role}.json"
        mapping.write_text(json.dumps(dict(schemaVersion=1, mappings=[dict(family="Synthetic Brand", role=role)])))
        output = directory / role
        require(generate(output, mapping, verified=True) == 0 and verify(output, mapping) == 0, role)

    require(generate(directory / "omitted", None, verified=True) == 2, "omitted generation mapping")
    require(verify(first, None) == 2, "omitted verify mapping")
    changed = directory / "changed.json"
    changed.write_text(map_file.read_text() + "\n")
    require(verify(first, changed) == 2, "map hash drift")

    mutations = {
        "missing-manifest": lambda p: (p / "manifest.json").unlink(),
        "source-drift": lambda p: (p / "FigmaProfileScreen.generated.kt").write_text("tampered"),
        "normalized-drift": lambda p: (p / "normalized-frame.json").write_text(
            (p / "normalized-frame.json").read_text().replace('"body"', '"title"')),
    }
    for label, mutate in mutations.items():
        output = directory / label
        shutil.copytree(first, output)
        mutate(output)
        before = contents(output)
        require(verify(output) == 2 and contents(output) == before, label)

    boolean_receipt = directory / "boolean-receipt"
    shutil.copytree(first, boolean_receipt)
    manifest = json.loads((boolean_receipt / "manifest.json").read_text())
    manifest["fontRoleBinding"]["version"] = True
    (boolean_receipt / "manifest.json").write_text(json.dumps(manifest))
    require(verify(boolean_receipt) == 2, "boolean receipt version")

    # Removing both manifest font fields must not turn mapped code into a no-map bundle.
    stripped = directory / "stripped"
    shutil.copytree(first, stripped)
    manifest = json.loads((stripped / "manifest.json").read_text())
    manifest.pop("fontRoleBinding")
    manifest.pop("fontMapSha256")
    (stripped / "manifest.json").write_text(json.dumps(manifest))
    require(verify(stripped, None) == 2, "stripped font receipt")
    normalized = json.loads((stripped / "normalized-frame.json").read_text())
    normalized["frame"]["children"][0].pop("fontRole")
    (stripped / "normalized-frame.json").write_text(json.dumps(normalized))
    require(verify(stripped, None) == 2, "stripped normalized role still requires map")

    for label, replacement in [("default", "fontRoleBody: FontFamily = FontFamily.SansSerif"),
                               ("wrong-parameter", "fontRoleTitle: FontFamily")]:
        output = directory / label
        shutil.copytree(first, output)
        code = output / "FigmaProfileScreen.generated.kt"
        code.write_text(code.read_text().replace("fontRoleBody: FontFamily", replacement))
        manifest = json.loads((output / "manifest.json").read_text())
        manifest["files"][code.name] = hashlib.sha256(code.read_bytes()).hexdigest()
        (output / "manifest.json").write_text(json.dumps(manifest))
        require(verify(output) == 2, f"rehashed {label}")

    unused = directory / "unused-parameter"
    shutil.copytree(first, unused)
    code = unused / "FigmaProfileScreen.generated.kt"
    code.write_text(code.read_text().replace("fontFamily = fontRoleBody", "fontFamily = FontFamily.SansSerif"))
    manifest = json.loads((unused / "manifest.json").read_text())
    manifest["files"][code.name] = hashlib.sha256(code.read_bytes()).hexdigest()
    (unused / "manifest.json").write_text(json.dumps(manifest))
    require(verify(unused) == 2, "rehashed unused required parameter")

    mutable = directory / "mutable.json"
    mutable.write_text(map_file.read_text())
    shim = directory / "drift-launcher.py"
    shim.write_text("#!/usr/bin/env python3\nimport subprocess,sys\nfrom pathlib import Path\n"
                   f"subprocess.run([{str(launcher)!r}]+sys.argv[1:],check=True)\n"
                   f"with Path({str(mutable)!r}).open('a') as stream: stream.write('\\n')\n")
    shim.chmod(0o755)
    result = subprocess.run([sys.executable, str(wrapper), "--launcher", str(shim), "--input", str(source),
                             "--node", "10:1", "--font-map", str(mutable), "--out", str(directory / "drift")],
                            capture_output=True)
    require(result.returncode == 2 and not (directory / "drift").exists(), "generation-time drift")

print("Font roles: deterministic required parameters, shared validation and omission/drift gates passed")
