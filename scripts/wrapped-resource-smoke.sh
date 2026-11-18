#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
launcher="${1:?expected launcher path}"
tmp="$(mktemp -d)"
trap 'rm -rf -- "$tmp"' EXIT
fixture=fixtures/figma/wrapped-resource.file-nodes.json
map=fixtures/figma/wrapped-resource/asset-map.json
emit() { "$launcher" generate-figma --input "$1" --node 42:1 --out "$2" --asset-map "$3" "${@:4}"; }
emit "$fixture" "$tmp/one" "$map" --flatten-instances
emit "$fixture" "$tmp/two" "$map" --flatten-instances
diff -r "$tmp/one" "$tmp/two"
diff -r sample-wrapped-resource-output "$tmp/one"
python3 - "$fixture" "$map" "$tmp" <<'PY'
import copy, hashlib, json, pathlib, sys
fixture,mp,out=map(pathlib.Path,sys.argv[1:])
source=json.loads(fixture.read_text())
mapping=json.loads(mp.read_text())
payload=(mp.parent/mapping["file"]).read_bytes()
receipt=json.loads((out/"one/manifest.json").read_text())
assert receipt["decorativeAsset"]["nodeId"]=="I42:3;43:6;43:7"
assert receipt["decorativeAsset"]["width"]==receipt["decorativeAsset"]["height"]==20
assert receipt["decorativeAsset"]["sha256"]==hashlib.sha256(payload).hexdigest()
assert (out/"one"/receipt["decorativeAsset"]["file"]).read_bytes()==payload
src=json.loads((out/"one/source-map.json").read_text())
assert src["instanceComponents"]=={"42:3":"43:2"}
lines=(out/"one"/src["file"]).read_text().splitlines()
assert "Column(" in lines[src["nodeLines"]["I42:3;43:6"]-1]
assert "Image(painter = painterResource(Res.drawable.mad_icon_synthetic_badge)" in lines[src["nodeLines"]["I42:3;43:6;43:7"]-1]
mutations={
 "wrong-owner":lambda i,w,l: i.update(id="42:8"),
 "wrong-wrapper":lambda i,w,l: w.update(id="I42:3;43:8"),
 "wrong-kind":lambda i,w,l: w.update(type="INSTANCE",componentId="43:6"),
 "visible-sibling":lambda i,w,l: w["children"].append({"id":"I42:3;43:6;43:9","type":"VECTOR"}),
 "hidden-sibling":lambda i,w,l: w["children"].append({"visible":False}),
 "hidden-wrapper":lambda i,w,l: w.update(visible=False),
 "style":lambda i,w,l: w.update(fills=[{"visible":False,"type":"SOLID"}]),
 "clip":lambda i,w,l: w.update(clipsContent=True),
 "padding":lambda i,w,l: w.update(paddingLeft=1),
 "opacity":lambda i,w,l: w.update(opacity=0.5),
 "wrapper-translation":lambda i,w,l: w.update(relativeTransform=[[1,0,1],[0,1,0]]),
 "leaf-translation":lambda i,w,l: l.update(relativeTransform=[[1,0,1],[0,1,0]]),
 "scale":lambda i,w,l: w.update(relativeTransform=[[2,0,0],[0,1,0]]),
 "bounds-width":lambda i,w,l: w["absoluteBoundingBox"].update(width=21),
 "bounds-offset":lambda i,w,l: l["absoluteBoundingBox"].update(x=62),
 "bounds-missing":lambda i,w,l: l["absoluteBoundingBox"].pop("y"),
 "unknown-wrapper":lambda i,w,l: w.update(unknownVisual=[]),
 "wrapper-reaction":lambda i,w,l: w.update(reactions=[{}]),
 "sibling-spoof":lambda i,w,l: i["children"].append(l) or w.update(children=[]),
}
for label,mutate in mutations.items():
    n=copy.deepcopy(source)
    i=n["nodes"]["42:1"]["document"]["children"][1]
    w=i["children"][0]
    mutate(i,w,w["children"][0])
    (out/(label+".json")).write_text(json.dumps(n))
PY
negative() {
    local label="$1" path="$2"; shift 2
    set +e
    emit "$path" "$tmp/fail-$label" "$map" "$@"
    local rc=$?
    set -e
    test "$rc" -eq 2
    grep -Fq '"ok": false' "$tmp/fail-$label/diagnostics.json"
    for f in manifest.json FigmaProfileScreen.generated.kt composeResources; do test ! -e "$tmp/fail-$label/$f"; done
}
negative default "$fixture"
for f in wrong-owner wrong-wrapper wrong-kind visible-sibling hidden-sibling hidden-wrapper style clip padding opacity wrapper-translation leaf-translation scale bounds-width bounds-offset bounds-missing unknown-wrapper wrapper-reaction sibling-spoof; do
    negative "$f" "$tmp/$f.json" --flatten-instances
done
grep -Fq 'matching expanded INSTANCE' "$tmp/fail-wrong-owner/diagnostics.json"
grep -Fq 'identical explicit bounds' "$tmp/fail-bounds-offset/diagnostics.json"
grep -Fq 'do not allow a local transform offset' "$tmp/fail-leaf-translation/diagnostics.json"
grep -Fq 'Unknown field on inert icon wrapper' "$tmp/fail-unknown-wrapper/diagnostics.json"
echo "Wrapped decorative resource binding smoke tests passed."
