# Explicit consumer font-family binding

The default adapter still accepts only generic `sans-serif`, `serif`, and `monospace`. Named families require an explicit opt-in map; they are never silently substituted with a generic font. This feature changes font-family selection only, not the SDK or the rest of the text style.

## Version 1 scope

```json
{"schemaVersion":1,"mappings":[{"family":"Synthetic Brand","role":"body"}]}
```

Exactly one named family may be bound per generation. Supported role names are `body`, `title`, `label`; they are stable parameter labels, not built-in Material typography styles. Matching is case-sensitive. A family is 1–100 ASCII letters/digits/spaces/underscores/hyphens with no leading/trailing whitespace. JSON escapes, duplicate/unknown keys, multiple or colliding bindings, generic-family remapping, unused bindings, and unknown roles are rejected. The map is an unlinked regular UTF-8 file of at most 4096 bytes. This intentionally narrow initial format does not yet support localized family names, fallback lists, mixed spans, font fetching, or multiple roles per screen.

A matched Text retains its supported numeric size, weight and line height. For `body`, the generated screen requires:

```kotlin
@Composable
fun FigmaProfileScreen(fontRoleBody: FontFamily) { /* generated content */ }
```

The host calls `FigmaProfileScreen(fontRoleBody = appTypography.bodyFontFamily)`. There is no generic default argument: consumers must supply their chosen FontFamily. Loading/licensing font files, fallback policy and actual font-face selection remain host responsibilities. Assigning a regular font file to FontWeight.Medium does not create or verify a 500 face. This is not full MaterialTheme typography inheritance or a guarantee of design-font pixel equivalence.

## Generate and verify before compilation

```sh
python3 scripts/generate-verified.py \
  --launcher build/install/MadUIKitCodegen/bin/MadUIKitCodegen \
  --input fixtures/figma/font-role.file-nodes.json --node 10:1 \
  --font-map fixtures/figma/font-role/map.json --out /tmp/new-font-bundle

python3 scripts/generate-verified.py --verify-only \
  --input fixtures/figma/font-role.file-nodes.json --node 10:1 \
  --font-map fixtures/figma/font-role/map.json --out /tmp/new-font-bundle
```

The wrapper generates in isolation and rejects existing output destinations. It resolves and hashes the exact map bytes, validates the font binding receipt, checks input/map drift, source hashes, normalized role and required generated signature/callsites. Verify-only is read-only and rejects missing maps or receipts, including removal of both manifest font fields from a mapped bundle. Invoke it as a required dependency of the consumer compile task and do not mutate the inputs/bundle between verification and compilation. Include the same flags (`--fit-width`, `--flatten-instances`, `--asset-map`) when used at generation.

Receipts record `fontMapSha256` and `fontRoleBinding` (version/family/role/parameter). Equivalent JSON with different whitespace intentionally has a different raw-byte hash. Without the new opt-in, existing successful output and existing unsupported-family diagnostics remain unchanged. Input `generate` mode does not accept mapped font roles.

These checks establish consistency with trusted inputs and generator output; they are not signed attestations against a malicious producer that rewrites all evidence. Host font bytes are outside the generator receipt. A runtime acceptance report should separately record source hash, font-file hash, density/font scale and platform.

## Tests

`./gradlew check` includes `fontRoleSmokeTest`. The checked-in synthetic fixture and Python suite exercise deterministic output, all supported roles, matching Kotlin/Python rejection behavior, missing/stripped receipts, drift and rehashed default/wrong parameter signatures. Tests need no network once the generator is built, no installed fonts and no live Figma account.

A separate local acceptance used the same generated 360×120 screen in two Compose Desktop consumers with DejaVu Sans/Serif. Each native render matched an independent handwritten reference; distinct visible glyph masks confirmed different family selection. This evidence does not establish Android, iOS, Harmony, production font licensing or real-design fidelity. Native binary caches and screenshots are deliberately excluded from this source patch.
