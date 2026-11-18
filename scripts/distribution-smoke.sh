#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
launcher="${1:?expected launcher path}"
tmp="$(mktemp -d)"
trap 'rm -rf -- "$tmp"' EXIT
fixture=fixtures/figma/distribution.file-nodes.json
emit() { "$launcher" generate-figma --input "$1" --node 25:1 --out "$2" --fit-width; }
emit "$fixture" "$tmp/one"
emit "$fixture" "$tmp/two"
diff -r "$tmp/one" "$tmp/two"
diff -r sample-distribution-output "$tmp/one"
kt="$tmp/one/FigmaProfileScreen.generated.kt"
grep -F 'horizontalArrangement = Arrangement.Absolute.SpaceBetween' "$kt"
grep -F 'verticalArrangement = Arrangement.SpaceBetween' "$kt"
! grep -Fq '25:6' "$tmp/one/source-map.json"
python3 - "$fixture" "$tmp" <<'PY'
import copy, json, pathlib, sys
source = json.loads(pathlib.Path(sys.argv[1]).read_text())
out = pathlib.Path(sys.argv[2])
for mode in ("hug-axis", "unbounded-parent", "insufficient", "gap", "malformed", "wrap", "fill-child", "grow-child", "one-visible", "bad-visibility", "column-hug", "cross-wrap", "fixed-row"):
    current = copy.deepcopy(source)
    root = current["nodes"]["25:1"]["document"]
    row, column = root["children"][1:]
    if mode == "hug-axis": row["layoutSizingHorizontal"] = "HUG"
    if mode == "unbounded-parent":
        root["children"][1] = {"id":"25:20","type":"FRAME","layoutMode":"VERTICAL",
            "layoutSizingHorizontal":"HUG","layoutSizingVertical":"HUG","children":[row]}
    if mode == "insufficient": row["absoluteBoundingBox"]["width"] = 100
    if mode == "gap": row["itemSpacing"] = 4
    if mode == "malformed": row["itemSpacing"] = "0"
    if mode == "wrap": row["layoutWrap"] = "WRAP"
    if mode == "fill-child": row["children"][0]["layoutSizingHorizontal"] = "FILL"
    if mode == "grow-child": row["children"][0]["layoutGrow"] = 1
    if mode == "one-visible":
        row["children"][1]["visible"] = False
        row["children"][3]["visible"] = False
    if mode == "bad-visibility": row["children"][0]["visible"] = "false"
    if mode == "column-hug": column["layoutSizingVertical"] = "HUG"
    if mode == "cross-wrap": row["counterAxisAlignContent"] = "SPACE_BETWEEN"
    if mode == "fixed-row": row["layoutSizingHorizontal"] = "FIXED"
    (out / f"{mode}.json").write_text(json.dumps(current))
for mode, fields in {"normalized-centered":{"mainCentered":True}, "normalized-string":{"mainSpaceBetween":"true"}, "normalized-gap":{"gap":3}}.items():
    data = json.loads((out / "one/normalized-frame.json").read_text())
    data["frame"]["children"][1].update(fields)
    (out / f"{mode}.json").write_text(json.dumps(data))
PY
emit "$tmp/fixed-row.json" "$tmp/fixed"
grep -F 'width(368.dp)' "$tmp/fixed/FigmaProfileScreen.generated.kt"
for test in hug-axis:mainSpaceBetween unbounded-parent:mainSpaceBetween insufficient:primaryAxisAlignItems gap:itemSpacing malformed:itemSpacing wrap:layoutWrap fill-child:primaryAxisAlignItems grow-child:primaryAxisAlignItems one-visible:mainSpaceBetween bad-visibility:visible column-hug:mainSpaceBetween cross-wrap:counterAxisAlignContent; do
    mode="${test%:*}"; field="${test#*:}"
    set +e
    emit "$tmp/$mode.json" "$tmp/fail-$mode"
    rc=$?
    set -e
    test "$rc" -eq 2
    grep -F "$field" "$tmp/fail-$mode/diagnostics.json"
    test ! -e "$tmp/fail-$mode/FigmaProfileScreen.generated.kt"
done
for mode in normalized-centered normalized-string normalized-gap; do
    set +e
    "$launcher" generate --input "$tmp/$mode.json" --out "$tmp/fail-$mode"
    rc=$?
    set -e
    test "$rc" -eq 2
    grep -F 'mainSpaceBetween' "$tmp/fail-$mode/diagnostics.json"
    test ! -e "$tmp/fail-$mode/SyntheticProfileScreen.generated.kt"
done
echo "Space-between distribution smoke tests passed."
