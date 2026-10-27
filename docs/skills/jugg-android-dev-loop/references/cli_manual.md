# Jugg CLI Manual

CLI entry: `python3 {SKILL_DIR}/scripts/jugg.py [global options] <subcommand> [options]`.
Flags accept kebab-case and camelCase, e.g. `--resource-id` / `--resourceId`.

### CLI Output Format

Put global options before the subcommand:

| `--console` | Output | Use |
|---|---|---|
| `plain` | key: value, no spinner; default for direct Python calls | agent / script |
| `json` | raw JSON `{status, message, data, ...}`, no spinner | structured data |
| `rich` | key: value with spinner; shell wrapper default | human only; never use for agents, as TUI refresh pollutes context |

Use `--project-dir <path>` or `--project-dir=<path>` to override the current directory. A subdirectory of an initialized Runtime project resolves to that parent project as `projectDir`.

```
python3 {SKILL_DIR}/scripts/jugg.py --project-dir /path/to/project --console=json deploy
```

Local help does not connect to MCP: `jugg.py --help`, `jugg.py help <command>`, or `jugg.py <command> --help`, using the CLI entry above.

## Version Command

`python3 {SKILL_DIR}/scripts/jugg.py version` shows the CLI version and plugin version across initialized IDE projects. If plugin versions differ, it also lists each project/version and shows the highest plugin version first.

## Build & Deploy Commands

Run `compile`, `deploy`, `gradle-build`, and `instrument` as one foreground CLI call and wait for process exit before parsing output once. They may trigger Gradle internally; do not background them or poll status, processes, logs, files, or Git to infer progress. Process exit is the only completion signal.
Completion means the MCP job reached a terminal state. CLI does not expose `waitAppReadyAfterSuccess` or add an app-ready wait after `deploy`, `gradle-build`, `clean-reinstall`, or `restart`.

| Command | Purpose / when to use |
|---|---|
| `compile` | Compile modified sources without deploy; default after ordinary edits or generic “verify/check modification” requests |
| `deploy` | Compile + deploy; launch/run the app for runtime, UI, or device-side verification |
| `gradle-build` | Full Gradle fallback required by `policy_incremental_compile_limits.md`, or after incremental retries are exhausted |
| `clean-reinstall` | Clear app data, compatible with apply changes, and launch on device; **only** for clearing app data |
| `instrument` | Compile, deploy, and run androidTest |

### `compile`/`deploy`/`gradle-build`/`clean-reinstall`

Invoke the CLI entry with the command above; no subcommand arguments are required.

### `instrument`

`--source-path` anchors resolution of the test class/method, androidTest module, and test APK to a file under `src/androidTest`.
After a successful `instrument`, app and androidTest changes are compiled/deployed into their APKs; raw `adb shell am instrument` may then run broad class/package/suite regression filters.

```
python3 {SKILL_DIR}/scripts/jugg.py instrument --source-path library1/src/androidTest/kotlin/com/example/FooTest.kt --class com.example.FooTest --method testSomething --runner androidx.test.runner.AndroidJUnitRunner --extras 'size=large;clearPackageData=true'
```

| Flag | Description |
|---|---|
| `--source-path <path>` | Required test source file under `src/androidTest` |
| `--class <fqcn>` | Optional test class in the file |
| `--method <name>` | Optional test method in the resolved class |
| `--runner <fqn>` | Instrumentation runner override |
| `--extras <k=v;k2=v2>` | Semicolon-separated extras |

Unsupported: package, testPackage, regex, `--clazz`, `--instrumentationRunner`, and raw `-e`. Use the flags above; refresh APKs with `instrument` before raw adb regression.
Without an AndroidTest full-build baseline, it returns `ERROR` / `INVALID_PARAMS` with `enabledAndroidTest=false`; follow the enable steps under [status](#status).

## Runtime & Observe Commands

| Command | Purpose |
|---|---|
| `restart` | Restart app |
| `wait-logs` | Wait for app log marker, crash, or timeout |
| `activity-stack` | Show current Activity stack |
| `tap` | Tap, long-press, or swipe on device |

### `restart`

`python3 {SKILL_DIR}/scripts/jugg.py restart` restarts the app without an extra app-ready wait.

### `wait-logs`

Blocks until marker, crash, or timeout, starting from the most-recent `deploy`/`restart` timestamp.

```
python3 {SKILL_DIR}/scripts/jugg.py wait-logs --marker '\[JUGG_AR\] DONE' --tags MyAutoRun,AndroidRuntime --timeout-ms 30000
```

| Flag | Description |
|---|---|
| `--marker <regex>` | **Required** Java Pattern regex matched against the log message |
| `--tags <t1,t2,...>` | Comma-separated tag whitelist; empty = no filter |
| `--timeout-ms <ms>` | Hard timeout `[1000, 300000]`, default 30000 |

Output `stopReason`: `marker` → parse `logs`; `crash` → FAIL; `timeout` → INCONCLUSIVE.

### `tap`

Prefer element selectors, then coordinates (px), then percentages:

```
python3 {SKILL_DIR}/scripts/jugg.py tap --text "Login"
python3 {SKILL_DIR}/scripts/jugg.py tap --x 540 --y 960
python3 {SKILL_DIR}/scripts/jugg.py tap --x-percent 50 --y-percent 80 --action swipe --end-x-percent 50 --end-y-percent 20
```

- Element selectors: `--text`, `--resource-id`, or `--content-desc`; `--class-name` can narrow the match.
- `--action <tap|long-press|swipe>` defaults to `tap`; add `--action long-press` for a long press.
- Percent taps use `--x-percent` / `--y-percent`; swipe also requires matching `--end-x` / `--end-y` or `--end-x-percent` / `--end-y-percent`.
- Elements share the Dragonfly dump node model. Compose taps dispatch a MotionEvent at node center; Semantics action, disabled-state, and stale-node behavior are not guaranteed.

## UI Inspection Commands

| Command | Purpose |
|---|---|
| `view-locate` | Find live element position, bounds, and source location |
| `view-inspect` | Read properties via reflection |
| `layout-dump` | Export sparse JSON in interaction or UI mode |

### `view-locate`

```
python3 {SKILL_DIR}/scripts/jugg.py view-locate --text "Item" --class-name TextView --max-results 5
python3 {SKILL_DIR}/scripts/jugg.py view-locate --resource-id hidden_label --visible-only false
```

- Require at least one of `--text`, `--resource-id`, `--content-desc`, or `--class-name`. Non-empty selectors use AND logic; class names match exact full or simple names.
- `--visible-only <true|false>` defaults to `true`; `--max-results <1..100>` defaults to `10`.
- Output: `matchCount`, `returnedCount`, `truncated`, `matches`; a unique match also has top-level `bounds [left,top,right,bottom]`, `position {x,y}`, and `size {width,height}`, all in dp. Source file/line are best-effort.
- If `matchCount > 1`, narrow the selector before using a candidate for verification or interaction.

### `view-inspect`

```
python3 {SKILL_DIR}/scripts/jugg.py view-inspect --text "Submit" "getText()" "getVisibility()"
python3 {SKILL_DIR}/scripts/jugg.py view-inspect --resource-id bubble_container "layoutParams.leftMargin" "getLayoutParams().getMarginStart()"
```

- Require at least one of `--text`, `--resource-id`, `--content-desc`, or `--class-name`; class names match exact full or simple names.
- Expressions are read-only getters, Kotlin properties, or public fields: e.g. `getWidth()`, `getTextSize()`, `getCurrentTextColor()`, `getBackground()`, `getTranslationY()`, `getAlpha()`, `isClickable()`, `isEnabled()`.
- Names without `()` resolve public fields first, then `getXxx()` / `isXxx()` (`layoutParams` → `getLayoutParams()`, `enabled` → `isEnabled()`).
- Hidden, non-clickable views still in the hierarchy may be inspected; hidden views are not safe tap targets.
- Android nodes inspect the original View; Compose nodes inspect the Dragonfly node, so View-only getters return per-expression errors.
- Output: expression/value/type pairs, density for px→dp conversion, and best-effort source file/line.

### `layout-dump`

```
python3 {SKILL_DIR}/scripts/jugg.py layout-dump --mode interaction
python3 {SKILL_DIR}/scripts/jugg.py layout-dump --mode ui --root-layout content_frame --include-gone
python3 {SKILL_DIR}/scripts/jugg.py layout-dump --all-windows
```

| Task | Mode / tool |
|---|---|
| Explore unfamiliar text, buttons, or navigation | `interaction` (default) |
| Validate geometry, spacing, original hierarchy, decoration, or hidden state | `ui`; add `--include-gone` for GONE nodes |
| Limit scope / include dialogs and popups | `--root-layout <id>` searches across windows for a subtree (View resource name, not `R.id.xxx`); `--all-windows` includes all windows |
| Locate or tap a known selector | Direct `view-locate` or element-mode `tap` |
| Read properties absent from the dump | `view-inspect` |
| Recreate UI structure and geometry | `ui` plus screenshots, assets, and source; dump alone lacks complete visual/source information |

**Read results:** `data.file` is the sparse JSON artifact; `contentBytes` is its UTF-8 size. Files ≤16 KiB (16,384 bytes, including separators) also return complete `data.layout` in either mode; use it without rereading the file. Larger results return the complete file without inline preview. This is a file-size threshold, not a response/token limit. CLI plain/json preserves Unicode; MCP exposes the object in `structuredContent.data.layout` and summarizes it in text.

The artifact includes mode, bounds/padding units, windows, density, budget `truncated`, and optional warnings. Sparse defaults: visible, alpha=1, clickable=false, enabled=true, zero padding, empty children; missing `textColor` may mean unavailable/default, not necessarily black.

Files are nested JSON, not JSONL, with real nodes on separate lines and inline synthetic `jugg_details` children. For large results, use a known selector or request a subtree; otherwise search text/IDs and read nearby lines before expanding. A few lines do not represent the complete hierarchy.

**Interaction mode:** removes invisible/gone subtrees even with `--include-gone`, omits padding/textColor, and retains className. Visible roots and nodes with real IDs, text, descriptions, clickability, or diagnostics remain; other wrappers are removed and children promoted, subject to offscreen folding below.

In ordinary windows, fully offscreen branches retain their first node's interaction fields but replace descendants with a `jugg_details` placeholder; offscreen leaves remain. Placeholders are not controls or counted nodes/clickables. Partially visible or zero-area nodes, and branches with intersecting descendants, unknown descendant geometry, or descendant diagnostics are not folded. Missing window bounds and rootLayout requests disable viewport folding, retaining semantic pruning. Use `--mode ui --root-layout <id>` for omitted descendants; order/bounds/className guide exploration, then re-query after swiping.

Interaction is a summary: retained nodes are not guaranteed actionable, and missing nodes/hierarchy cannot prove absence or original parentage. Pruning/folding does not set budget `truncated`.

**UI mode and limits:** preserves the captured hierarchy, hidden nodes, and visual fields within scope; GONE requires `--include-gone`. Field meanings, window/subtree scope, and collection limits are shared across modes. Incomplete/truncated trees cannot prove absence. Queries/actions use fresh Dragonfly snapshots; re-query after interaction. The 5000-node/60-level boundary also limits selectors, taps, inspections, and verification. Private runtime supports Java-only projects.

Virtual IDs use `_vid_<hash>`; selectors also accept legacy `_vir_id_`. Compose IDs remain stable only while traversal order and UI structure stay unchanged; Compose tap limitations are described under [tap](#tap).

## Diagnostic Commands

| Command | Purpose |
|---|---|
| `status` | Deploy readiness and pending changed-file summary |
| `devices` | List connected devices |
| `ssh-info` | Remote troubleshooting info; **requires user consent** |

### `status`

```
python3 {SKILL_DIR}/scripts/jugg.py status [--refresh-changes <true|false>] [--full-info <true|false>]
```

- Refreshes git-tracked changed files by default; `--refresh-changes false` skips it.
- Returns at most 20 pending paths by default; `--full-info true` returns full status and all pending paths.
- `data.executionType=remote`: remote Gradle fallback is configured; hooks block the first raw Gradle attempt even without a source write in this agent session.
- Before choosing androidTest / `instrument`, reuse credible existing status (e.g. a hook block's `Jugg status` output), or run `--console=json status`.
- `data.enabledAndroidTest=true` means the latest persisted full-build baseline used AndroidTest target. If false, **do not run instrument**: open Jugg App Run Configuration, enable Android Test / `enableAndroidTest`, run a full build / `gradle-build`, then re-check status until true.

### `ssh-info`

```
python3 {SKILL_DIR}/scripts/jugg.py ssh-info --reason "deploy fails after 3 retries, gradle-build also fails"
```

`--reason` is required; use only after all other fallback steps are exhausted and with user consent.

### Non-CLI: `adb logcat`

For long-tail logs outside Jugg flow, run directly via shell:

```bash
adb logcat -d -s <TAG>
```
