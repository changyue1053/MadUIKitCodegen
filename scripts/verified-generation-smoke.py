#!/usr/bin/env python3
"""Public synthetic acceptance tests; no network or design credentials."""
import copy
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("verified", ROOT / "scripts/generate-verified.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
launcher = str(Path(sys.argv[1]).resolve())
checks = 0
with tempfile.TemporaryDirectory() as tmp:
    work = Path(tmp)
    def run(name, input_name, extra=(), expected=0, custom_launcher=None):
        global checks
        out = work / name
        result = subprocess.run([sys.executable, str(ROOT / "scripts/generate-verified.py"),
            "--launcher", custom_launcher or launcher, "--input", str(ROOT / input_name),
            "--out", str(out), *extra], capture_output=True, text=True)
        assert result.returncode == expected, (result.stdout, result.stderr)
        checks += 1
        return out
    plain = run("plain", "fixtures/synthetic-profile.json")
    raw = work / "raw"
    subprocess.run([launcher, "generate", "--input", str(ROOT / "fixtures/synthetic-profile.json"), "--out", str(raw)], check=True)
    assert {p.name:p.read_bytes() for p in plain.iterdir()} == {p.name:p.read_bytes() for p in raw.iterdir()}
    wrapped = run("wrapped", "fixtures/figma/wrapped-resource.file-nodes.json", ["--node", "42:1", "--flatten-instances", "--asset-map", str(ROOT / "fixtures/figma/wrapped-resource/asset-map.json")])
    run("fit", "fixtures/figma/distribution.file-nodes.json", ["--node", "25:1", "--fit-width"])
    (work / "real-parent/inside").mkdir(parents=True)
    (work / "alias-parent").symlink_to(work / "real-parent", target_is_directory=True)
    run("alias-parent/inside/bundle", "fixtures/synthetic-profile.json")
    (work / "existing-output-link").symlink_to(plain, target_is_directory=True)
    run("existing-output-link", "fixtures/synthetic-profile.json", expected=2)
    before = {p.name:p.read_bytes() for p in plain.iterdir()}
    run("plain", "fixtures/synthetic-profile.json", expected=2)
    assert before == {p.name:p.read_bytes() for p in plain.iterdir()}
    run("missing", "fixtures/absent.json", expected=2)
    run("bad-node", "fixtures/figma/wrapped-resource.file-nodes.json", ["--node", "999:99"], expected=2)
    run("bad-flags", "fixtures/synthetic-profile.json", ["--fit-width"], expected=2)
    fake = work / "fake.sh"
    fake.write_text('#!/bin/sh\nexit 0\n')
    fake.chmod(0o700)
    run("false-success", "fixtures/synthetic-profile.json", expected=2, custom_launcher=str(fake))
    assert not any((work/name).exists() for name in ["missing", "bad-node", "bad-flags", "false-success"])
    assert not list(work.glob(".mad-generation-*"))
    args = (module.digest(ROOT / "fixtures/figma/wrapped-resource.file-nodes.json"), "42:1", False, True,
            module.digest(ROOT / "fixtures/figma/wrapped-resource/asset-map.json"))
    def mutate_json(path, fn):
        value = json.loads(path.read_text())
        fn(value)
        path.write_text(json.dumps(value))
    def mutation(label, fn):
        global checks
        target = work / label
        shutil.copytree(wrapped, target)
        fn(target)
        try:
            module.verify(target, *args)
        except (ValueError, OSError):
            checks += 1
        else:
            raise AssertionError(f"Accepted mutation: {label}")
    mutation("kotlin-tamper", lambda p: (p/"FigmaProfileScreen.generated.kt").write_text("bad"))
    mutation("png-tamper", lambda p: (p/"composeResources/drawable/mad_icon_synthetic_badge.png").write_bytes(b"bad"))
    mutation("diag-failed", lambda p: mutate_json(p/"diagnostics.json", lambda v:v.update(ok=False)))
    mutation("diag-hash", lambda p: mutate_json(p/"diagnostics.json", lambda v:v.update(inputSha256="0"*64)))
    mutation("wrong-node", lambda p: mutate_json(p/"manifest.json", lambda v:v.update(frameId="99:1")))
    mutation("wrong-mode", lambda p: mutate_json(p/"manifest.json", lambda v:v.update(rootWidthMode="fit-host")))
    mutation("wrong-map", lambda p: mutate_json(p/"manifest.json", lambda v:v.update(assetMapSha256="0"*64)))
    mutation("extra-file", lambda p:(p/"unlisted.kt").write_text("stale"))
    mutation("missing-kotlin", lambda p:(p/"FigmaProfileScreen.generated.kt").unlink())
    mutation("bad-metadata", lambda p:(p/"manifest.json").write_text("[]"))
    mutation("duplicate-key", lambda p:(p/"manifest.json").write_text('{"schemaVersion":1,"schemaVersion":1}'))
    mutation("traversal", lambda p:mutate_json(p/"manifest.json", lambda v:v["decorativeAsset"].update(file="../outside.png")))
    mutation("map-mismatch", lambda p:mutate_json(p/"source-map.json", lambda v:v.update(file="other.kt")))
    def linked(p):
        png=p/"composeResources/drawable/mad_icon_synthetic_badge.png"
        png.unlink()
        png.symlink_to(wrapped/"composeResources/drawable/mad_icon_synthetic_badge.png")
    mutation("linked-png", linked)
print(f"Verified generation safety: {checks} checks passed")
