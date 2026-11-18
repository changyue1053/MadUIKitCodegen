#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
launcher="${1:?expected launcher path}"
tmp="$(mktemp -d)"
trap 'rm -rf -- "$tmp"' EXIT
fixture=fixtures/figma/resource.file-nodes.json
map=fixtures/figma/resource/asset-map.json
name=mad_icon_synthetic_diamond.png
emit() { "$launcher" generate-figma --input "$1" --node 30:1 --out "$2" --asset-map "$3"; }
emit "$fixture" "$tmp/one" "$map"
emit "$fixture" "$tmp/two" "$map"
diff -r "$tmp/one" "$tmp/two"
diff -r sample-resource-output "$tmp/one"
cmp "fixtures/figma/resource/$name" "$tmp/one/composeResources/drawable/$name"
grep -F 'painterResource(Res.drawable.mad_icon_synthetic_diamond)' "$tmp/one/FigmaProfileScreen.generated.kt"
python3 - "$fixture" "$map" "$tmp" <<'PY'
import copy, hashlib, json, pathlib, struct, sys

fixture,map_path,out=map(pathlib.Path,sys.argv[1:])
base=json.loads(fixture.read_text())
mapping=json.loads(map_path.read_text())
original=(map_path.parent / mapping["file"]).read_bytes()
def scene(label, change):
    data=copy.deepcopy(base)
    icon=data["nodes"]["30:1"]["document"]["children"][1]
    change(icon)
    (out / (label+".json")).write_text(json.dumps(data))
for label, change in {
    "resize":lambda i:i["absoluteBoundingBox"].update({"width":21}),
    "wrong-type":lambda i:i.update({"type":"RECTANGLE"}),
    "hidden":lambda i:i.update({"visible":False}),
    "hug":lambda i:i.update({"layoutSizingHorizontal":"HUG"}),
    "non-leaf":lambda i:i.update({"children":[{}]}),
    "bad-pose":lambda i:i.update({"rotation":25}),
    "interactive":lambda i:i.update({"interactions":[{}]}),
}.items(): scene(label,change)
for label, overrides, payload in (
    ("traversal",{"file":"../"+mapping["file"]},None),
    ("url",{"file":"https://example.invalid/x.png"},None),
    ("bad-digest",{"sha256":"0"*64},original),
    ("missing",{},None),
    ("wrong-node",{"nodeId":"30:9"},original),
    ("unknown-key",{"url":"unsupported"},original),
    ("symlink",{},None),
    ("truncated",{},original[:-5]),
    ("trailing",{},original+b"suffix"),
    ("oversize",{},bytes(32769)),
):
    target=out/label
    target.mkdir()
    m=mapping|overrides
    if payload is not None:
        (target/mapping["file"]).write_bytes(payload)
        if label in {"truncated","trailing","oversize"}:
            m["sha256"]=hashlib.sha256(payload).hexdigest()
    if label=="symlink":
        (target/mapping["file"]).symlink_to((map_path.parent/mapping["file"]).resolve())
    (target/"map.json").write_text(json.dumps(m))

receipt=json.loads((out/"one/manifest.json").read_text())
leaf=receipt["decorativeAsset"]
assert leaf["nodeId"]=="30:3" and leaf["width"]==leaf["height"]==20
assert leaf["sha256"]==mapping["sha256"]==hashlib.sha256((out/"one"/leaf["file"]).read_bytes()).hexdigest()
assert receipt["assetMapSha256"]==hashlib.sha256(map_path.read_bytes()).hexdigest()
assert leaf["file"]=="composeResources/drawable/"+mapping["file"]
assert receipt["files"][leaf["file"]]==leaf["sha256"]
assert "30:3" in json.loads((out/"one/source-map.json").read_text())["nodeLines"]
PY
negative() {
    local n="$1" out="$tmp/bad-$1"; shift
    set +e
    "$@" >"$tmp/$n.log" 2>&1
    local status=$?
    set -e
    test "$status" -eq 2
    test ! -f "$out/FigmaProfileScreen.generated.kt"
    test ! -f "$out/manifest.json"
    grep -Fq '"ok": false' "$out/diagnostics.json"
}
negative default "$launcher" generate-figma --input "$fixture" --node 30:1 --out "$tmp/bad-default"
grep -Fq 'Unsupported Figma node type: VECTOR' "$tmp/bad-default/diagnostics.json"
for label in resize wrong-type hidden hug non-leaf bad-pose interactive; do
    negative "$label" emit "$tmp/$label.json" "$tmp/bad-$label" "$map"
done
for label in traversal url bad-digest missing wrong-node unknown-key symlink truncated trailing oversize; do
    negative "$label" emit "$fixture" "$tmp/bad-$label" "$tmp/$label/map.json"
done
set +e
"$launcher" generate --input "$tmp/one/normalized-frame.json" --out "$tmp/unbound"
rc=$?
set -e
test "$rc" -eq 2
grep -Fq 'matching verified --asset-map' "$tmp/unbound/diagnostics.json"
test ! -f "$tmp/unbound/SyntheticProfileScreen.generated.kt"

mkdir "$tmp/bad-output-link" "$tmp/other-output"
ln -s "$tmp/other-output" "$tmp/bad-output-link/composeResources"
negative output-link emit "$fixture" "$tmp/bad-output-link" "$map"
test ! -e "$tmp/other-output/drawable/$name"
echo "Decorative local PNG resource smoke tests passed."
