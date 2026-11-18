#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
launcher="${1:?usage: figma-smoke.sh <absolute-launcher-path>}"
tmp="$(mktemp -d)"
trap 'rm -rf -- "$tmp"' EXIT
profile=fixtures/figma/profile.file-nodes.json
emit() { "$launcher" generate-figma --input "$1" --node "$2" --out "$3"; }
reject() {
  local rc=0
  emit "$1" "$2" "$3" || rc="$?"
  [[ "$rc" == 2 ]]
  [[ ! -e "$3/FigmaProfileScreen.generated.kt" ]]
  [[ ! -e "$3/manifest.json" ]]
}
emit "$profile" 1:2 "$tmp/first"
emit "$profile" 1:2 "$tmp/second"
diff -r "$tmp/first" "$tmp/second"
diff -r sample-figma-output "$tmp/first"
grep -F 'fun FigmaProfileScreen()' "$tmp/first/FigmaProfileScreen.generated.kt"
grep -F 'Spacing' "$tmp/first/FigmaProfileScreen.generated.kt"
grep -F 'width(344.dp).height(240.dp).clipToBounds()' "$tmp/first/FigmaProfileScreen.generated.kt"
grep -F 'left = 20.5f.dp, top = 18.dp' "$tmp/first/FigmaProfileScreen.generated.kt"
grep -F 'Color(0xCCE1EEFF)' "$tmp/first/FigmaProfileScreen.generated.kt"
grep -F '"1:7":' "$tmp/first/source-map.json"
grep -F '"figmaSourceVersion": "synthetic-p1-v1"' "$tmp/first/manifest.json"
grep -F '"adapterVersion": 2' "$tmp/first/manifest.json"
! grep -Fq '"1:8":' "$tmp/first/source-map.json"
reject fixtures/figma/unsupported.file-nodes.json 1:2 "$tmp/unsupported"
grep -F '"nodeId": "1:3"' "$tmp/unsupported/diagnostics.json"
grep -F 'Unsupported Figma node type: VECTOR' "$tmp/unsupported/diagnostics.json"
grep -F 'Gradient requires three normalized handles' "$tmp/unsupported/diagnostics.json"
grep -F 'Unsupported value: WRAP' "$tmp/unsupported/diagnostics.json"
reject "$profile" 9:9 "$tmp/unavailable"
grep -F 'Requested node is absent or unavailable' "$tmp/unavailable/diagnostics.json"
reject "$profile" 1-2 "$tmp/noncanonical"
grep -F 'Expected canonical Figma node ID' "$tmp/noncanonical/diagnostics.json"
sed 's/"sans-serif"/"Inter"/g' "$profile" > "$tmp/named-font.json"
reject "$tmp/named-font.json" 1:2 "$tmp/named-font"
grep -F 'Named font requires an explicit font asset/mapping' "$tmp/named-font/diagnostics.json"
sed 's/"id": "1:2", "name": "Profile"/"id": "1:2", "relativeTransform": [[0,1,0],[-1,0,0]], "name": "Profile"/' "$profile" > "$tmp/rotated.json"
reject "$tmp/rotated.json" 1:2 "$tmp/rotated"
grep -F 'rotation, scale, and shear are unsupported' "$tmp/rotated/diagnostics.json"
echo 'P1 Figma adapter smoke tests passed.' 
