#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
launcher="${1:?usage: gradient-smoke.sh <absolute-launcher-path>}"
tmp="$(mktemp -d)"
trap 'rm -rf -- "$tmp"' EXIT
fixture=fixtures/figma/gradient.file-nodes.json
emit() { "$launcher" generate-figma --input "$1" --node 3:1 --out "$2" "${@:3}"; }
emit "$fixture" "$tmp/one" --fit-width
emit "$fixture" "$tmp/two" --fit-width
diff -r "$tmp/one" "$tmp/two"
diff -r sample-gradient-output "$tmp/one"
grep -F 'Modifier.fillMaxWidth().height(330.dp)' "$tmp/one/FigmaProfileScreen.generated.kt"
grep -F 'figmaAxisGradient(true, 0f, 1f, 0f to Color(0xFF1F3687), 0.48f to Color(0xFF542691), 1f to Color(0xFF0D6B6B))' "$tmp/one/FigmaProfileScreen.generated.kt"
grep -F 'figmaAxisGradient(false, 1f, 0f' "$tmp/one/FigmaProfileScreen.generated.kt"
grep -F 'Brush.linearGradient(*stops, start = start, end = end)' "$tmp/one/FigmaProfileScreen.generated.kt"
grep -F '"rootWidthMode": "fit-host"' "$tmp/one/manifest.json"
grep -F '"3:3":' "$tmp/one/source-map.json"
emit "$fixture" "$tmp/fixed"
grep -F 'Modifier.width(344.dp).height(330.dp)' "$tmp/fixed/FigmaProfileScreen.generated.kt"
! grep -Fq 'rootWidthMode' "$tmp/fixed/manifest.json"
test ! -e "$tmp/fixed/SyntheticProfileScreen.generated.kt"

python3 - "$fixture" "$tmp" <<'PY'
import copy, json, pathlib, sys
value = json.loads(pathlib.Path(sys.argv[1]).read_bytes())
dst = pathlib.Path(sys.argv[2])
root = value["nodes"]["3:1"]["document"]
hero = root["children"][1]["fills"][0]
original = copy.deepcopy(hero)
hero["gradientHandlePositions"][1]["y"]=0.75
(dst/"diagonal.json").write_text(json.dumps(value))
hero.update(copy.deepcopy(original))
hero["gradientStops"][1]["position"]=0
(dst/"duplicate-stops.json").write_text(json.dumps(value))
hero.update(copy.deepcopy(original))
hero["type"]="GRADIENT_RADIAL"
(dst/"radial.json").write_text(json.dumps(value))
hero.update(copy.deepcopy(original))
hero["gradientStops"][0]["color"]["a"]=0.5
hero["opacity"]=0.5
(dst/"opacity.json").write_text(json.dumps(value))
hero.update(copy.deepcopy(original))
root["layoutMode"]="HORIZONTAL"
root["children"]=[]
(dst/"unsupported-responsive.json").write_text(json.dumps(value))
PY
reject() {
  local rc=0
  emit "$tmp/$1.json" "$tmp/$1" --fit-width || rc="$?"
  [[ "$rc" == 2 ]]
  [[ ! -e "$tmp/$1/FigmaProfileScreen.generated.kt" ]]
  [[ ! -e "$tmp/$1/manifest.json" ]]
  grep -F "$2" "$tmp/$1/diagnostics.json"
}
reject diagonal 'Only axis-aligned, nondegenerate'
reject duplicate-stops 'Gradient stops must be strictly ascending'
reject radial 'Only one visible, unbound SOLID'
reject unsupported-responsive 'Responsive root width requires a VERTICAL frame'
emit "$tmp/opacity.json" "$tmp/opacity" --fit-width
grep -F '0f to Color(0x401F3687)' "$tmp/opacity/FigmaProfileScreen.generated.kt"
echo 'P5 gradient and responsive smoke tests passed.'
