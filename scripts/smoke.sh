#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
launcher="${1:?usage: smoke.sh <absolute-launcher-path>}"
tmp="$(mktemp -d)"
trap 'rm -rf -- "$tmp"' EXIT
gen() { "$launcher" generate --input "$1" --out "$2"; }
reject() {
  local rc=0
  gen "$1" "$2" || rc="$?"
  [[ "$rc" == 2 ]]
  [[ ! -e "$2/SyntheticProfileScreen.generated.kt" ]]
}
gen fixtures/synthetic-profile.json "$tmp/first"
gen fixtures/synthetic-profile.json "$tmp/second"
diff -r "$tmp/first" "$tmp/second"
diff -r sample-output "$tmp/first"
grep -F 'package dev.maduikit.demo.generated' "$tmp/first/SyntheticProfileScreen.generated.kt"
grep -F 'onClick = onSave' "$tmp/first/SyntheticProfileScreen.generated.kt"
root_line="$(grep -n '    Column(' "$tmp/first/SyntheticProfileScreen.generated.kt" | head -1 | cut -d ':' -f1)"
grep -F "\"frame:profile\": $root_line" "$tmp/first/source-map.json"
grep -F '"ok": true' "$tmp/first/diagnostics.json"
grep -F "$(shasum -a 256 "$tmp/first/SyntheticProfileScreen.generated.kt" | cut -d ' ' -f1)" "$tmp/first/manifest.json"
reject fixtures/unsupported.json "$tmp/unsupported"
grep -F '"nodeId": "gradient:70"' "$tmp/unsupported/diagnostics.json"
grep -F 'Unsupported node type: GradientMesh' "$tmp/unsupported/diagnostics.json"
cat > "$tmp/duplicate.json" <<'JSON'
{"schemaVersion":1,"frame":{"id":"dup","type":"Column","children":[{"id":"dup","type":"Text","text":"测试"}]}}
JSON
reject "$tmp/duplicate.json" "$tmp/duplicate"
grep -F 'Duplicate node ID' "$tmp/duplicate/diagnostics.json"
cat > "$tmp/unsupported-field.json" <<'JSON'
{"schemaVersion":1,"frame":{"id":"root","type":"Column","children":[{"id":"shape","type":"Box","children":[],"blur":12}]}}
JSON
reject "$tmp/unsupported-field.json" "$tmp/unsupported-field"
grep -F '"path": "$.frame.children[0].blur"' "$tmp/unsupported-field/diagnostics.json"
printf '{\n' > "$tmp/malformed.json"
reject "$tmp/malformed.json" "$tmp/malformed"
grep -F 'Invalid JSON' "$tmp/malformed/diagnostics.json"
cat > "$tmp/escaped.json" <<'JSON'
{"schemaVersion":1,"frame":{"id":"profile","type":"Column","children":[{"id":"txt","type":"Text","text":"\" $world\\\n 😃"}]}}
JSON
gen "$tmp/escaped.json" "$tmp/escaped"
grep -F '\$world' "$tmp/escaped/SyntheticProfileScreen.generated.kt"
echo 'P0 codegen smoke tests passed.'
