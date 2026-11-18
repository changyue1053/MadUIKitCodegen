#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
launcher="${1:?expected launcher path}"
tmp="$(mktemp -d)"
trap 'rm -rf -- "$tmp"' EXIT
fixture=fixtures/figma/rounded.file-nodes.json
emit() { "$launcher" generate-figma --input "$1" --node 3:1 --out "$2" --fit-width; }
emit "$fixture" "$tmp/one"
emit "$fixture" "$tmp/two"
diff -r "$tmp/one" "$tmp/two"
diff -r sample-rounded-output "$tmp/one"
kt="$tmp/one/FigmaProfileScreen.generated.kt"
grep -F 'shape = AbsoluteRoundedCornerShape(topLeft = 28.dp, topRight = 4.dp, bottomRight = 22.5f.dp, bottomLeft = 12.dp)' "$kt"
grep -F '.clip(AbsoluteRoundedCornerShape(topLeft = 18.dp, topRight = 18.dp, bottomRight = 18.dp, bottomLeft = 18.dp)).background' "$kt"
! grep -Fq '.clip(AbsoluteRoundedCornerShape(topLeft = 28.dp' "$kt"
python3 - "$fixture" "$tmp" <<'PY'
import copy, json, pathlib, sys
source = json.loads(pathlib.Path(sys.argv[1]).read_text())
out = pathlib.Path(sys.argv[2])
for mode, fields in {
    "smoothing": {"cornerSmoothing": .6},
    "negative": {"cornerRadius": -1},
    "bad-array": {"rectangleCornerRadii": [3, 4, 5]},
    "bad-string": {"rectangleCornerRadii": [3, 4, "5", 6]},
    "conflict": {"rectangleCornerRadii": [3, 4, 5, 6]},
    "solid-unclipped": {"clipsContent": False},
}.items():
    current = copy.deepcopy(source)
    current["nodes"]["3:1"]["document"]["children"][2].update(fields)
    (out / f"{mode}.json").write_text(json.dumps(current))
PY
emit "$tmp/solid-unclipped.json" "$tmp/solid-out"
grep -F 'background(Color(0xFFC2EBE8), AbsoluteRoundedCornerShape(topLeft = 18.dp, topRight = 18.dp, bottomRight = 18.dp, bottomLeft = 18.dp))' "$tmp/solid-out/FigmaProfileScreen.generated.kt"
for test in smoothing:cornerSmoothing negative:cornerRadius bad-array:rectangleCornerRadii bad-string:rectangleCornerRadii conflict:rectangleCornerRadii; do
    mode="${test%:*}"
    field="${test#*:}"
    if emit "$tmp/$mode.json" "$tmp/fail-$mode"; then
        printf 'unexpected success: %s\n' "$mode" >&2; exit 1
    fi
    grep -F "$field" "$tmp/fail-$mode/diagnostics.json"
    test ! -e "$tmp/fail-$mode/FigmaProfileScreen.generated.kt"
done
echo "Circular corner smoke tests passed."
