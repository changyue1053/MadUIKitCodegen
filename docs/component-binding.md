# Opt-in controlled filter-tab binding

`generate-figma --input snapshot.json --node 10:1 --out bundle --flatten-instances --component-map binding.json`

Prefer `scripts/generate-verified.py` with the same flags and `--launcher` pointing to the installed generator. Before compiling retained output, invoke the wrapper with the same input and maps and `--verify-only`. Do not modify verified inputs or bundle files between verification and compilation. Hash receipts detect accidental drift; they are not signatures or trust attestations.

This deliberately narrow authored mapping supports one expanded, fixed-size, horizontal INSTANCE directly inside one horizontal FRAME, containing one TEXT child. Both the exact TEXT label property and BOOLEAN enabled property must be authored in the sidecar. Snapshot instance.componentId must resolve to a unique matching component key within the selected node's authoritative components map. Root-level, missing, duplicate or conflicting metadata is rejected. A name match alone never binds a component.

See `fixtures/figma/component-binding/binding.json` and `snapshot.json` for the entirely synthetic example. Extra sidecar fields, properties, variants, actions, unsupported appearance, fit-width and resource maps are rejected, not silently approximated. Retained slot layout/text are emitted through the existing public `com.mad.maduikit.MadFilterTabs` and `MadFilterTab` API. The consumer must supply the compatible SDK dependency and required `selected: Boolean` and `onSelect: () -> Unit` parameters. Label text comes from the snapshot; enabled comes from the BOOLEAN property. The generator owns neither app state nor business actions. It does not create state, guess callbacks, design selected-state appearance, or publish Figma Code Connect mappings.

The optional existing named-font map is supported simultaneously, adding its required FontFamily argument. No new SDK API is introduced. With no component map, previous generation behavior and bundle bytes are unchanged. Normalized component annotations are internal outputs of this opt-in conversion, not a new standalone normalized-input API.

## JSON policy

For component-enabled snapshot and sidecar inputs only: strict UTF-8 without BOM, standard JSON whitespace/syntax, unique decoded keys in every object (including escaped aliases and authoritative metadata), Unicode scalar strings, finite numeric values within Double range, and maximum container/value depth 128. Sidecar size is 1–4096 bytes, regular unlinked file; schemaVersion is the integer token 1, not 1.0 or true. Binding strings are 1–128 UTF-16 code units; componentKey is ASCII letters/digits/underscore/hyphen. Label and enabled keys must differ. Legal JSON string escapes are supported. Enabled is a boolean, never a string or number. Strictness is opt-in, not an undocumented global change to the legacy JSON reader.

## Evidence and limits

The synthetic Desktop gate compiled generated code with unmodified public SDK API source and compared native rendered frames against a separate handwritten reference. Real pointer press/release produced exactly one enabled callback and zero disabled callbacks. This proves this narrow component contract, not arbitrary Figma conversion, mobile runtime support or versioned Maven integration. The selected-state visual is consumer-owned; a touch ripple is not a selected design.

`componentBindingSmokeTest` is wired into Gradle `check`, covering deterministic receipts, exact legacy hashes from 66c86cf, typed-property/appearance rejection, lexical ambiguity and verify-before-compile omission/tamper checks. Standalone: `python3 scripts/component-binding-smoke.py <installed-launcher>`.
