#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
launcher="${1:?expected launcher path}"
tmp="$(mktemp -d)"
trap 'rm -rf -- "$tmp"' EXIT
fixture=fixtures/figma/compound-resource.file-nodes.json
map=fixtures/figma/compound-resource/asset-map.json
emit() {
    "$launcher" generate-figma --input "$1" --node 40:1 --out "$2" --asset-map "$3" "${@:4}"
}
emit "$fixture" "$tmp/one" "$map" --flatten-instances
emit "$fixture" "$tmp/two" "$map" --flatten-instances
diff -r "$tmp/one" "$tmp/two"
diff -r sample-compound-resource-output "$tmp/one"
python3 - "$fixture" "$map" "$tmp" <<'PY'
import copy, hashlib, json, pathlib, sys
fixture,mp,out=map(pathlib.Path,sys.argv[1:])
source=json.loads(fixture.read_text())
base=json.loads(mp.read_text())
payload=(mp.parent/base["file"]).read_bytes()
for label in ("container-frame","orphan","foreign-owner","extra-hop","bad-id","wrapper","hidden","instance-leaf","duplicate","owner-reaction","parent-binding"):
    dr=out/label
    dr.mkdir()
    data=copy.deepcopy(source)
    mapping=base.copy()
    children=data["nodes"]["40:1"]["document"]["children"]
    inst=children[1]
    leaf=inst["children"][0]
    if label=="container-frame": inst.update(type="FRAME")
    if label=="orphan": children[1]=leaf
    if label=="foreign-owner": mapping["nodeId"]=leaf["id"]="I40:8;41:7"
    if label=="extra-hop": mapping["nodeId"]=leaf["id"]="I40:3;41:8;41:9;41:7"
    if label=="bad-id": mapping["nodeId"]=leaf["id"]="I40:3;../41:7"
    if label=="wrapper":
        inst["children"]=[{"id":"I40:3;41:9","type":"FRAME","layoutMode":"VERTICAL",
            "layoutSizingHorizontal":"FIXED","layoutSizingVertical":"FIXED",
            "absoluteBoundingBox":{"width":20,"height":20},"children":[leaf]}]
    if label=="hidden": leaf.update(visible=False)
    if label=="instance-leaf": leaf.update(type="INSTANCE",componentId="41:7")
    if label=="duplicate": inst["children"].append(copy.deepcopy(leaf))
    if label=="owner-reaction": inst.update(reactions=[{}])
    if label=="parent-binding": mapping["nodeId"]="40:3"
    (dr/"frame.json").write_text(json.dumps(data))
    (dr/"map.json").write_text(json.dumps(mapping))
    (dr/mapping["file"]).write_bytes(payload)
manifest=json.loads((out/"one/manifest.json").read_text())
assert manifest["instanceMode"]=="expanded-visual-v1"
assert manifest["decorativeAsset"]["nodeId"]==base["nodeId"]=="I40:3;41:7"
resource=manifest["decorativeAsset"]["file"]
assert (out/"one"/resource).read_bytes()==payload
assert hashlib.sha256(payload).hexdigest()==manifest["files"][resource]==base["sha256"]
source_map=json.loads((out/"one/source-map.json").read_text())
assert source_map["instanceComponents"]=={"40:3":"41:2"}
kotlin=(out/"one"/source_map["file"]).read_text().splitlines()
line=source_map["nodeLines"]["I40:3;41:7"]
assert "Image(painter = painterResource(Res.drawable.mad_icon_synthetic_window)" in kotlin[line-1]
PY
negative() {
    local label="$1"; shift
    set +e
    emit "$1" "$tmp/fail-$label" "$2" "${@:3}"
    local rc=$?
    set -e
    test "$rc" -eq 2
    grep -Fq '"ok": false' "$tmp/fail-$label/diagnostics.json"
    test ! -f "$tmp/fail-$label/manifest.json"
    test ! -f "$tmp/fail-$label/FigmaProfileScreen.generated.kt"
    test ! -d "$tmp/fail-$label/composeResources"
}
negative default "$fixture" "$map"
for t in container-frame orphan foreign-owner extra-hop bad-id wrapper hidden instance-leaf duplicate owner-reaction parent-binding; do
    negative "$t" "$tmp/$t/frame.json" "$tmp/$t/map.json" --flatten-instances
done
grep -Fq 'matching, explicitly expanded INSTANCE' "$tmp/fail-foreign-owner/diagnostics.json"
grep -Fq 'direct or single-wrapper expanded instance child ID' "$tmp/fail-extra-hop/diagnostics.json"
grep -Fq 'non-root VECTOR leaf' "$tmp/fail-instance-leaf/diagnostics.json"
echo "Compound resource binding smoke tests passed."
