# Jugg CLI Manual

CLI entry: `python3 {SKILL_DIR}/scripts/jugg.py [global options] <subcommand> [options]`.

### CLI Output Format

Controlled by the global `--console` flag (must appear before the subcommand):

| Value | Spinner | Output style | Typical use |
|-------|---------|--------------|-------------|
| `plain` | off | human-readable key: value (default) | agent / script |
| `rich` | on | human-readable key: value | human interactive terminal (set by shell wrappers) |
| `json` | off | raw JSON `{status, message, data, ...}` | agent needing structured data |

**DO NOT USE** `--console=rich` on agent, TUI refresh behavior will pollute the context.

Example:
```
python3 {SKILL_DIR}/scripts/jugg.py --console=json deploy
```

Use `--project-dir <path>` when the command must target a project different from the current working directory:
```
python3 {SKILL_DIR}/scripts/jugg.py --project-dir /path/to/project status
python3 {SKILL_DIR}/scripts/jugg.py --project-dir=/path/to/project --console=json deploy
```

When the path is a subdirectory of a project already initialized by the connected Runtime, the CLI sends that parent project directory as `projectDir`.

All flags accept both kebab-case (`--resource-id`) and camelCase (`--resourceId`).

Print local help without connecting to MCP:
```
python3 {SKILL_DIR}/scripts/jugg.py --help
python3 {SKILL_DIR}/scripts/jugg.py help deploy
python3 {SKILL_DIR}/scripts/jugg.py deploy --help
```

---

## Version Command

```
python3 {SKILL_DIR}/scripts/jugg.py version
```

Show CLI version and Jugg plugin version from all initialized IDE projects.

Output when all projects share the same version:
```
cli version: 1.0.1
plugin version: 1.2.3
```

Output when projects have differing versions (highest version shown first):
```
cli version: 1.0.1
plugin version: 1.2.3
  (versions differ across projects)
  /path/to/projectA: 1.2.3
  /path/to/projectB: 1.2.0
```

---

## Build & Deploy Commands

All build commands **block** until completion. Run compile-class commands (`compile`, `deploy`, `gradle-build`, and `instrument`) as a single foreground CLI call, wait for that process to exit, then parse its output once. These commands may trigger Gradle internally; do not start them in the background or poll `status`, processes, logs, files, or Git state to infer progress. Process exit is the only completion signal.
Completion means the MCP compile/deploy job has reached a terminal state. The CLI does not expose
`waitAppReadyAfterSuccess`, so it does not add an extra app-ready wait after `deploy`, `gradle-build`,
`clean-reinstall`, or `restart`.

| Command | Purpose | When to Use |
|---------|---------|-------------|
| `compile` | Compile modified sources, skip deploy | Default after ordinary source edits, including generic "verify/check modification" |
| `deploy` | Compile + deploy to device | Need to launch/run app to inspect runtime/UI state, or perform device-side verification |
| `gradle-build` | Full Gradle compile fallback | Direct fallback required by `policy_incremental_compile_limits.md`, or `deploy`/`compile` retries exhausted |
| `clean-reinstall` | Clear app data(compat with apply changes) + launch device | **Only** for clean APP data |
| `instrument` | Run androidTest | Verify android test result |

### `compile`/`deploy`/`gradle-build`/`clean-reinstall`

```
python3 {SKILL_DIR}/scripts/jugg.py compile
python3 {SKILL_DIR}/scripts/jugg.py deploy
python3 {SKILL_DIR}/scripts/jugg.py gradle-build
python3 {SKILL_DIR}/scripts/jugg.py clean-reinstall
```

### `instrument`

Runs androidTest through Jugg compile/deploy chain, while keeping parameter style close to `am instrument`.
The command is source-file anchored: `--source-path` identifies the `src/androidTest` file used to resolve the test class/method, androidTest module, and test APK.
After one successful `jugg instrument`, all app source changes and androidTest source changes have been compiled and deployed into their corresponding APKs. If you then need broad androidTest regression coverage, raw `adb shell am instrument` is acceptable for normal class/package/suite filtering.

```
python3 {SKILL_DIR}/scripts/jugg.py instrument --source-path library1/src/androidTest/kotlin/com/example/FooTest.kt
python3 {SKILL_DIR}/scripts/jugg.py instrument --source-path library1/src/androidTest/kotlin/com/example/FooTest.kt --class com.example.FooTest
python3 {SKILL_DIR}/scripts/jugg.py instrument --source-path library1/src/androidTest/kotlin/com/example/FooTest.kt --class com.example.FooTest --method testSomething
python3 {SKILL_DIR}/scripts/jugg.py instrument --source-path library1/src/androidTest/kotlin/com/example/FooTest.kt --runner androidx.test.runner.AndroidJUnitRunner --extras 'size=large;clearPackageData=true'
```

| Flag | Description |
|------|-------------|
| `--source-path <path>` | test source file under `src/androidTest`; required by the MCP tool. |
| `--class <fqcn>` | optional test class in the source file. |
| `--method <name>` | optional test method in the resolved class. |
| `--runner <fqn>` | instrumentation runner override. |
| `--extras <k=v;k2=v2>` | batch extras format. |

Unsupported by `jugg instrument`: package, testPackage, regex, `--clazz`, `--instrumentationRunner`, and raw `-e`. Use `--source-path` with optional `--class`, `--method`, `--runner`, and `--extras`; for broad regression, first refresh APKs with `jugg instrument`, then use raw `adb shell am instrument`.

When the project has no AndroidTest full-build baseline, `instrument` returns `ERROR` / `INVALID_PARAMS` with `enabledAndroidTest=false` and the same enable steps as the pre-flight section: open the Jugg App Run Configuration, enable Android Test / `enableAndroidTest`, run a full build / `gradle-build`, then re-check `status`.


---

## Runtime & Observe Commands

| Command | Purpose |
|---------|---------|
| `restart` | Restart app |
| `wait-logs` | Block until app log marker, crash, or timeout |
| `activity-stack` | Show current Activity stack |
| `tap` | Tap/long-press/swipe on device |

### `restart`

```
python3 {SKILL_DIR}/scripts/jugg.py restart
```

### `wait-logs`

Block until a log marker appears, a crash is detected, or the timeout expires.
Uses the most-recent `deploy`/`restart` timestamp as the log start point.

```
python3 {SKILL_DIR}/scripts/jugg.py wait-logs --marker '\[JUGG_AR\] DONE'
python3 {SKILL_DIR}/scripts/jugg.py wait-logs --marker '\[JUGG_AR\] DONE' --tags MyAutoRun,AndroidRuntime --timeout-ms 30000
```

| Flag | Description |
|------|-------------|
| `--marker <regex>` | Java Pattern regex matched against log message. **Required.** |
| `--tags <t1,t2,...>` | Tag whitelist, comma-separated. Empty = no filter. |
| `--timeout-ms <ms>` | Hard timeout `[1000, 300000]`, default 30000. |

Output `stopReason`: `marker` → parse `logs`; `crash` → FAIL; `timeout` → INCONCLUSIVE.

### `tap`

Selector priority: Element → Coordinate → Percent.

```
python3 {SKILL_DIR}/scripts/jugg.py tap --text "Login"                          # element (preferred)
python3 {SKILL_DIR}/scripts/jugg.py tap --resource-id btn_submit                # element by ID
python3 {SKILL_DIR}/scripts/jugg.py tap --content-desc "Close button"           # element by content-desc
python3 {SKILL_DIR}/scripts/jugg.py tap --x 540 --y 960                         # coordinate (px)
python3 {SKILL_DIR}/scripts/jugg.py tap --x-percent 50 --y-percent 80           # percent (last resort)
python3 {SKILL_DIR}/scripts/jugg.py tap --text "Item" --action long-press        # long-press
python3 {SKILL_DIR}/scripts/jugg.py tap --x-percent 50 --y-percent 80 --action swipe --end-x-percent 50 --end-y-percent 20   # swipe
```

- `--action {tap|long-press|swipe}` — default: `tap`.
- `swipe` requires `--end-x/--end-y` or `--end-x-percent/--end-y-percent`.
- All flags also accept camelCase (= MCP param name), e.g. `--resourceId`, `--xPercent`.
- Element selectors use the same Dragonfly node model as layout dump. Compose taps currently dispatch a MotionEvent at node center and cannot guarantee Semantics action, disabled-state, or stale-node behavior.

---

## UI Inspection Commands

| Command | Purpose |
|---------|---------|
| `view-locate` | Find live element position, bounds, and source location |
| `view-inspect` | Query View properties via reflection |
| `layout-dump` | Export sparse JSON in interaction or UI mode |

### `view-locate`

```
python3 {SKILL_DIR}/scripts/jugg.py view-locate --text "Submit"
python3 {SKILL_DIR}/scripts/jugg.py view-locate --resource-id btn_confirm
python3 {SKILL_DIR}/scripts/jugg.py view-locate --content-desc "Back"
python3 {SKILL_DIR}/scripts/jugg.py view-locate --class-name android.widget.Button
python3 {SKILL_DIR}/scripts/jugg.py view-locate --text "Item" --class-name TextView --max-results 5
python3 {SKILL_DIR}/scripts/jugg.py view-locate --resource-id hidden_label --visible-only false
```

All non-empty selectors use AND logic. `className` accepts an exact full or simple class name.
`visibleOnly` defaults to `true`; `maxResults` defaults to `10` and accepts `1..100`.
The result reports total/returned counts and truncation. A unique match also exposes top-level
bounds/position/size; source file and line are included when the runtime can provide them.

At least one of `--text`/`--resource-id`/`--content-desc`/`--class-name` is required.
Output always includes `matchCount`, `returnedCount`, `truncated`, and `matches`; a unique match also includes
`bounds [left,top,right,bottom]`, `position {x,y}`, and `size {width,height}`. All coordinates are dp.
If `matchCount > 1`, narrow the selector before relying on one candidate for verification or interaction.

### `view-inspect`

```
python3 {SKILL_DIR}/scripts/jugg.py view-inspect --text "Submit" "getText()" "getVisibility()"
python3 {SKILL_DIR}/scripts/jugg.py view-inspect --resource-id btn_confirm "getBackground()" "getTextSize()"
python3 {SKILL_DIR}/scripts/jugg.py view-inspect --content-desc "Avatar" "getWidth()" "getHeight()" "getTranslationY()"
python3 {SKILL_DIR}/scripts/jugg.py view-inspect --resource-id bubble_container "layoutParams.leftMargin" "getLayoutParams().getMarginStart()"
```

- Selector: at least one of `--text`/`--resource-id`/`--content-desc`/`--class-name`; class name is an exact full or simple name.
- `<expr>`: read-only getter, Kotlin property, or public field expression. Common: `getText()`, `getVisibility()`, `layoutParams.leftMargin`, `getWidth()`, `getHeight()`, `getTextSize()`, `getCurrentTextColor()`, `getBackground()`, `getTranslationX()`, `getTranslationY()`, `getAlpha()`, `isClickable()`, `isEnabled()`.
- A name without `()` is resolved as a public field first, then as `getXxx()` / `isXxx()` (`layoutParams` → `getLayoutParams()`, `enabled` → `isEnabled()`).
- `view-inspect` may read non-clickable hidden views that stay in the hierarchy; hidden views are not safe tap targets.
- Android nodes inspect their original View; Compose nodes inspect the Dragonfly node object, so Android View-only getters return a per-expression error.
- Output: expression/value/type pairs + density for px→dp conversion, plus best-effort source file and line when available.

### `layout-dump`

```
python3 {SKILL_DIR}/scripts/jugg.py layout-dump
python3 {SKILL_DIR}/scripts/jugg.py layout-dump --mode interaction           # browse interaction candidates (default)
python3 {SKILL_DIR}/scripts/jugg.py layout-dump --mode ui                    # preserve captured hierarchy for UI validation
python3 {SKILL_DIR}/scripts/jugg.py layout-dump --root-layout content_frame   # cross-window subtree (View resource name, not R.id.xxx)
python3 {SKILL_DIR}/scripts/jugg.py layout-dump --mode ui --include-gone      # include GONE views
python3 {SKILL_DIR}/scripts/jugg.py layout-dump --all-windows                 # all windows (dialogs, popups)
```

Output: `data.file` points to a sparse JSON artifact; `contentBytes` is its UTF-8 byte size. The file includes `mode`, `units` for bounds/padding, windows, device density, budget `truncated`, and optional `warnings`. Empty fields and defaults are omitted: visibility=visible, alpha=1, clickable=false, enabled=true, zero padding, and empty children. Missing textColor does not uniquely mean black: the collector also omits unavailable/default values.

Files up to 16 KiB (16,384 UTF-8 bytes, including node separators) also return the complete JSON object in `data.layout`, in either mode. Use that object directly instead of rereading the artifact. Larger results omit `data.layout` and keep the complete file; they do not inline a partial preview. The threshold applies to the artifact, not the response envelope or a token budget. CLI plain/json output preserves Unicode; MCP carries the object only in `structuredContent.data.layout`, with a summary in text content.

Each real hierarchy node starts on a new line in the file; its fields and coordinate arrays stay compact. Synthetic `jugg_details` children stay inline. Children arrays containing real nodes put their opening `[` and closing `]}` on separate lines. This is one nested JSON document, not JSONL. For large results, prefer a known selector with `view-locate`, or request `--root-layout` for the relevant subtree. When the target is unknown, search the file for relevant text/IDs and read nearby lines before expanding the scope. A few file lines do not represent the complete hierarchy.

| Task | Mode / tool |
|---|---|
| Browse an unfamiliar page and discover text, buttons, or navigation | `layout-dump --mode interaction` |
| Validate geometry, spacing, original hierarchy, decorative nodes, or hidden state | `layout-dump --mode ui`; add `--include-gone` or `--all-windows` when needed |
| Locate or tap a known selector | Call `view-locate` or element-mode `tap` directly |
| Read properties absent from the dump, such as text size or background | `view-inspect` |
| Recreate UI structure and geometry | `ui` plus available screenshots, assets, and source; the dump does not contain complete visual or source information |

Interaction mode first removes invisible/gone nodes and their entire subtrees, even with `--include-gone`, and omits `padding` and `textColor`; `className` remains. Apart from the offscreen retention rules below, it keeps visible window roots and nodes with real IDs, text, descriptions, clickability, or diagnostics; other nodes are removed and children are promoted. Both modes expose virtual IDs as `_vid_<hash>`; selectors also accept legacy `_vir_id_` IDs. This mode is a page summary, not a list of guaranteed actionable elements. Its absence and hierarchy cannot establish element absence or original parent-child relationships. Use a live query or UI mode for further evidence. Pruning does not set `truncated`.

Interaction mode also folds fully offscreen branches in ordinary windows: the first offscreen node keeps its interaction fields, and its descendants are replaced by one synthetic child containing only `jugg_details`: `View is off-screen; descendants omitted in interaction mode.` Offscreen leaves remain. The placeholder is not a control and is excluded from node/clickable counts. Branches with intersecting descendants, unknown descendant geometry, or descendant diagnostics are not folded; partially visible and zero-area nodes are not folded either. Missing window bounds and rootLayout subtree requests keep the existing semantic pruning without viewport folding. Use `--mode ui --root-layout <id>` to inspect omitted descendants. Order, bounds, and className provide exploration clues; collect fresh evidence after swiping. Folding does not set `truncated`.

UI mode preserves the captured tree, hidden nodes, and visual fields within the requested scope; GONE nodes require `--include-gone`. Both modes retain the same field meanings, window/subtree scope, and collection limits. A truncated or incomplete tree cannot prove absence. All app-side UI queries and actions use fresh Dragonfly snapshots; collect fresh evidence after interaction. The 5000-node/60-level snapshot boundary also limits selectors, taps, inspections, and verification. Java-only projects are supported by the private runtime. A Compose virtual ID remains stable only while traversal order and UI structure are unchanged. Compose element taps dispatch a MotionEvent at the node center rather than invoking a Semantics action.

---

## Diagnostic Commands

| Command | Purpose |
|---------|---------|
| `status` | Show deploy readiness and pending changed-file summary |
| `devices` | List connected devices |
| `ssh-info` | Remote troubleshooting info (**requires user consent**) |

### `status`

```
python3 {SKILL_DIR}/scripts/jugg.py status [--refresh-changes <true|false>] [--full-info <true|false>]
```

`status` refreshes git-tracked changed files by default. Pass `--refresh-changes false` to skip the refresh.

By default, `status` returns at most 20 pending file paths. Pass `--full-info true` to return full status information, including all pending file paths.

`status` returns `data.executionType` and `data.enabledAndroidTest`. `executionType=remote` means the current Jugg run configuration uses remote Gradle fallback; command hooks block the first raw Gradle attempt in that mode even when the current agent session has no recorded source write. Reuse an existing hook block's `Jugg status` output when it is already in context; otherwise run `--console=json status` before choosing the androidTest / `instrument` route. `enabledAndroidTest=true` means the latest persisted full-build baseline used AndroidTest target.

If a user asks to run androidTest or instrumented unit tests and `enabledAndroidTest=false`, do not run `instrument`. Tell the user to open the Jugg App Run Configuration, enable Android Test / `enableAndroidTest`, run the configuration once with a full build / `gradle-build`, then re-check `status` until `data.enabledAndroidTest=true`.

### `ssh-info`

```
python3 {SKILL_DIR}/scripts/jugg.py ssh-info --reason "deploy fails after 3 retries, gradle-build also fails"
```

`--reason` is required. Only use after all other fallback steps exhausted and with user consent.

### Non-CLI: `adb logcat`

`adb logcat` is run directly via shell, not through `jugg`. Use for long-tail log collection outside Jugg flow.

```bash
adb logcat -d -s <TAG>                       # filter by tag
adb logcat -d | grep -E "\\[JUGG_AR\\]"         # filter auto-run logs
```
