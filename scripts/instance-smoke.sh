#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
launcher="${1:?usage: instance-smoke.sh <absolute-launcher-path>}"
tmp="$(mktemp -d)"
trap 'rm -rf -- "$tmp"' EXIT
fixture=fixtures/figma/instance.file-nodes.json
emit() { "$launcher" generate-figma --input "$1" --node 10:1 --out "$2" "${@:3}"; }
reject() {
  local rc=0
  emit "$@" || rc="$?"
  [[ "$rc" == 2 ]]
  [[ ! -e "$2/FigmaProfileScreen.generated.kt" ]]
  [[ ! -e "$2/manifest.json" ]]
}

emit "$fixture" "$tmp/first" --flatten-instances
emit "$fixture" "$tmp/second" --flatten-instances
diff -r "$tmp/first" "$tmp/second"
emit "$fixture" "$tmp/both" --fit-width --flatten-instances
emit "$fixture" "$tmp/both-reversed" --flatten-instances --fit-width
diff -r "$tmp/both" "$tmp/both-reversed"
grep -F 'Synthetic override' "$tmp/first/FigmaProfileScreen.generated.kt"
grep -F 'verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally' "$tmp/first/FigmaProfileScreen.generated.kt"
grep -F 'horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically' "$tmp/first/FigmaProfileScreen.generated.kt"
grep -F '"I10:2;40:2":' "$tmp/first/source-map.json"
grep -F '"10:2": "40:1"' "$tmp/first/source-map.json"
grep -F '"instanceMode": "expanded-visual-v1"' "$tmp/first/manifest.json"
grep -F '"rootWidthMode": "fit-host"' "$tmp/both/manifest.json"
reject "$fixture" "$tmp/not-opted"
grep -F 'Unsupported Figma node type: INSTANCE' "$tmp/not-opted/diagnostics.json"
reject "$fixture" "$tmp/duplicate-flag" --flatten-instances --flatten-instances
reject "$fixture" "$tmp/unknown-flag" --unsupported-option

python3 - "$fixture" "$tmp" <<'PY'
import copy, json, pathlib, sys

base = json.loads(pathlib.Path(sys.argv[1]).read_text())
dst = pathlib.Path(sys.argv[2])

for name in ("Collapsed", "BadComponent", "UnsupportedChild", "Interactive",
             "BadAlignment", "BadDescendantId", "CenteredFrame"):
    case = copy.deepcopy(base)
    root = case["nodes"]["10:1"]["document"]
    inst = root["children"][0]
    if name == "Collapsed":
        inst["children"] = []
    elif name == "BadComponent":
        inst["componentId"] = "not-an-id"
    elif name == "UnsupportedChild":
        inst["children"][0]["type"] = "VECTOR"
    elif name == "Interactive":
        inst["reactions"] = [{"description": "Synthetic test action"}]
    elif name == "BadAlignment":
        inst["primaryAxisAlignItems"] = "SPACE_BETWEEN"
    elif name == "BadDescendantId":
        inst["children"][0]["id"] = "I10:2;invalid"
    else:
        inst["type"] = "FRAME"
        del inst["componentId"]
        for i, child in enumerate(inst["children"]):
            child["id"] = f"10:{i + 3}"
    (dst / f"{name}.json").write_text(json.dumps(case))
PY

for case in Collapsed BadComponent UnsupportedChild Interactive BadAlignment BadDescendantId; do
  reject "$tmp/$case.json" "$tmp/$case" --flatten-instances
done
grep -F 'collapsed instances are not supported' "$tmp/Collapsed/diagnostics.json"
grep -F 'Expected canonical component ID' "$tmp/BadComponent/diagnostics.json"
grep -F 'Unsupported Figma node type: VECTOR' "$tmp/UnsupportedChild/diagnostics.json"
grep -F 'reactions' "$tmp/Interactive/diagnostics.json"
grep -F 'Unsupported value: SPACE_BETWEEN' "$tmp/BadAlignment/diagnostics.json"
grep -F 'canonical Figma instance descendant ID' "$tmp/BadDescendantId/diagnostics.json"

emit "$tmp/CenteredFrame.json" "$tmp/frame"
grep -F 'horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)' "$tmp/frame/FigmaProfileScreen.generated.kt"
! grep -Fq 'instanceMode' "$tmp/frame/manifest.json"
! grep -Fq 'instanceComponents' "$tmp/frame/source-map.json"
echo 'Expanded visual instance and centered Auto Layout smoke tests passed.'
