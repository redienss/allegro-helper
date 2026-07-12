# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & run

```bash
./build.sh                 # javac -> build/classes (+ build/allegro-helper.jar if the `jar` tool exists)
./run.sh                   # desktop UI
./run.sh --cli all         # headless: import | match | whitebalance | brightness | contrast | autocrop | ocr | describe | all
./run.sh --cli retouch /path/to/base-dir   # retouch = alias for whitebalance + brightness + contrast
```

`build.sh` resolves `javac` via `$JAVAC` → `PATH` → `$JAVA_HOME/bin` → `/usr/lib/jvm/*`, `/opt/*/bin`, `/snap/*/*/jbr/bin`. Override with `JAVAC=/path/to/javac ./build.sh`. If `jar` is missing (as on this machine), the jar step is skipped and `run.sh` runs from `build/classes` — that is a supported path, not a failure.

`build.sh` also copies `logo/logo.png` and `icons/AllegroHelper-icon-full-logo-1024.png` onto the classpath as `com/allegrohelper/ui/{logo,app-icon}.png`. The images have one source of truth on disk; don't add duplicates under `src/main/resources`.

## Testing

**There is no test framework, no test sources, and no test runner.** Don't look for one or assume `./build.sh` runs tests. Verification is manual, and these are the techniques that work here:

- **Compile check**: `./build.sh` (fast, no deps to resolve).
- **Pipeline behavior**: run `--cli <step>` against a **copy** of a base directory, never the live one.
- **Never make a live paid OpenAI call.** To exercise the `describe` step's plumbing, point it at a dead endpoint: `OPENAI_BASE_URL=http://127.0.0.1:9 OPENAI_API_KEY=dummy ./run.sh --cli describe <dir>`.
- **Never drive the live Allegro site.** To exercise `AllegroForm`/`Cdp`, pre-launch a headless Chrome on a scratch profile (`google-chrome --headless=new --user-data-dir=<scratch> --remote-debugging-port=0 --no-first-run about:blank`) — `Cdp.ensureChrome` reuses it via `DevToolsActivePort` — and fill a `file://` URL of a saved copy of the form (the user keeps one in `allegro-form/`, gitignored). Verify with `eval` readbacks of the field values.
- **Swing UI**: `java.awt.Robot` hangs under Wayland. Render offscreen instead — `frame.getRootPane().printAll(g2d)` after `addNotify()` + `validate()` — and assert on the component tree. `MainWindow` exposes `getFrame()` and `selectOfferRow(int)` for exactly this. Write throwaway probes under `$CLAUDE_JOB_DIR/tmp`, not in the repo.

## Working with the real data (read this before running anything)

The repo root doubles as the default base directory, and it holds the user's real, unversioned data. `.gitignore` covers `.env`, `build/`, `raw_photos/`, `offers/`, `more_data_*.txt`, `.chrome-profile/` (the Allegro **login session** of the app-driven Chrome), and `allegro-form/` (a form page saved while logged in — embeds account data).

- `.env` holds a **real** `OPENAI_API_KEY`. Never commit it; redact it whenever printing the file.
- `offers/`, `raw_photos/`, and `more_data_*.txt` are user data, not fixtures. Run destructive or write-heavy steps against a copy in a scratch dir.
- If `offers/` or `raw_photos/` turns up empty or altered unexpectedly, **stop and surface it** rather than regenerating or "repairing" it.
- Check screenshots for exposed secrets before committing, and review the staged set for `build/`, `.env`, and offer data.

## Architecture

Java 25, Swing, **zero external dependencies** — JDK standard library only (Swing, `java.net.http`, `javax.imageio`). No Maven/Gradle. Keep it that way: `util/Json` and `util/Csv` are hand-rolled precisely to avoid a dependency, so reach for them rather than adding a library.

### The pipeline

`core/Workflow` is the spine. It runs an ordered `List<Step>` (`IMPORT → MATCH → WHITE_BALANCE → BRIGHTNESS → CONTRAST → AUTOCROP → OCR → DESCRIBE`), logging a `== <label> ==` header per step and translating each step's 0..1 `stepProgress` into overall progress. Both front ends (`ui/MainWindow`, `cli/Cli`) do nothing but choose steps and supply a `Workflow.Listener`; all pipeline logic lives in `core/`.

Each step is a static `run`/`runAll(Config, Reporter)` and talks to the outside world only through `Reporter` (log lines + progress). That indirection is why the same code serves the UI and the CLI — don't print to stdout from `core/`.

Data flows through the filesystem, not in memory. A step reads the previous step's directory and writes its own:

```
offers/<YYYYMMDD_HHMM>/
  data.json       # CSV row + photo list        (match)
  photos/         # originals                   (match)
  white_balanced/ # gray-world white balance    (white balance)
  brightened/     # brightness at BRIGHTNESS_STRENGTH (brightness)
  contrasted/     # contrast at CONTRAST_STRENGTH (contrast)
  cropped/        # cropped to the item         (auto-crop)
  more_data.txt   # copied from more_data_<N>.txt
  ocr.txt         # text read off the photos    (ocr)
  description.txt                               (describe)
```

Because any retouching step can be unticked, downstream steps take the
most-processed input available: `Retouch.inputDir` walks `Mode` backwards from
the step (so brightness reads `white_balanced/` else `photos/`, contrast reads
`brightened/` else `white_balanced/` else `photos/`); auto-crop reads
`contrasted/` else `brightened/` else `white_balanced/` else the legacy
`retouched/` (the pre-split combined output — still recognized so old offers
keep working, but never written anymore) else `photos/`. Originals
still carry EXIF orientation, which ImageIO ignores, so auto-crop applies
`Exif` orientation on every decode (detection and cropping) to work in
upright coordinates.

**Two invariants hold across every step, and new code must preserve them:**

1. **Idempotence.** Re-running any step skips already-processed offers (typically: output dir exists and its entry count matches the input's) and logs that it skipped. `Start` with all boxes ticked must be safe to press twice.
2. **Fail loudly, or bail conservatively — never guess.** A `PipelineException` aborts the whole run with `Aborted: <message>` (e.g. `match` refuses to move anything when the detected series count differs from the CSV row count). Where a wrong result is worse than no result, the step logs why and leaves the offer untouched — see `AutoCrop`'s guards on short series, implausibly small subjects, and near-full-frame boxes.

### Notable step internals

- **`SeriesRecognition`** is the single entry point for grouping the photo dir into series; both `GroupAndMatch` and `PhoneScan` (the UI's Photos list) go through it so the preview always matches what a run would do. Three modes, chosen by `SERIES_RECOGNITION` / the UI dropdown: `auto` delegates to `Clustering` — timestamps from OpenCamera filenames (`IMG_YYYYMMDD_HHMMSS.jpg`), a gap > `SERIES_GAP_THRESHOLD_SECONDS` starts a new series; `single` treats every photo in the dir as one offer and uses **only the first CSV row**; `subfolders` makes each subfolder one offer (name order, offer dirs named after the subfolders; `ImportPhotos` preserves the subfolder structure in this mode). The non-auto modes use file mtimes, not filenames — the photos may come from any camera. Series are matched to CSV rows **by position**, so row order is load-bearing.
- **`Retouch`** backs all three retouching steps (`Retouch.Mode.WHITE_BALANCE` / `BRIGHTNESS` / `CONTRAST`): a gray-world white balance and two *strength dials*, both PIL's (`ImageEnhance.Brightness`: scale every channel by `BRIGHTNESS_STRENGTH`, which keeps hue because the channels keep their ratios; `ImageEnhance.Contrast`: push every pixel away from the photo's own mean luminance by `CONTRAST_STRENGTH`). 1.0 = no-op for both, clamped to 0.5..2.0; brightness runs *before* contrast, or it would move the mean the contrast pivots on. Contrast used to be PIL's `ImageOps.autocontrast(cutoff=1)`, which adapts per photo and so cannot be dialled — the slider on the Retouch Preview tab replaced it. `Exif` reproduces `ImageOps.exif_transpose` because ImageIO ignores orientation; deviating from PIL's semantics there is a behavior change, not a cleanup. Note the idempotence skip ignores the strengths: an offer already retouched is not redone at a new setting without Delete Output Files.
- **`AutoCrop`** separates item from background by *time*, not brightness or edges: on a turntable only the item's pixels change, so the mask is the per-pixel luminance range over the series, Otsu-thresholded, reduced to the largest connected blob. One box per series, grown by a margin and expanded to the source aspect ratio. It decodes via `ImageReadParam.setSourceSubsampling` (point-sampled — Pillow's smoothed resize gives a slightly different Otsu threshold, so Java boxes are tighter than a Python prototype's).
- **`Ocr`** shells out to the `tesseract` CLI (an external *program*, like the gvfs-mtp mount — the zero-dependency rule is about Java libraries) and writes `ocr.txt`, reading the most-processed photo dir like auto-crop does. Tesseract expects scans, not photos, so each frame is upscaled 2x and OCRed at both 0° and 180° (turntable items often face the camera with their label upside down; tesseract's OSD can't tell on mostly-object frames), keeping the higher-confidence rotation and dropping low-confidence words. Results are logged and appended to `ocr.txt` photo by photo; a `.ocr-in-progress` marker (removed on completion) tells a finished `ocr.txt` — skipped on re-run, even when empty — from one left by an interrupted run, which is redone. Missing tesseract aborts the run with an install hint.
- **`GenerateDescription`** posts to OpenAI Chat Completions. Price and `inpost_size` are appended from the CSV, never generated. `more_data.txt` and a non-empty `ocr.txt` ride along in the offer JSON (`additional_notes` / `ocr_text`). Skips offers that already have `description.txt`.

### Adding a pipeline step

Four places, all of which must agree:

1. `core/Workflow.Step` — add the enum constant *in execution order*.
2. `core/Workflow.runStep` — add the `case` (the switch is exhaustive, so the compiler will find it).
3. `cli/Cli` — add the `case` to the step switch, to the `"all"` list, and to the usage string.
4. `ui/MainWindow` — add a `JCheckBox`, add it to the Workflow `boxes` grid (which sizes itself from `Workflow.Step.values().length`; a `FlowLayout` there would silently clip a step, since `capHeight` pins the section to one row's height), and add it to `selectedSteps()`.

### UI

`MainWindow` (~1200 lines) is the only class that knows about Swing layout.

- The right panel's seven tabs are addressed exclusively through the `TAB_*` constants (`DESCRIPTION_INPUT=0`, `DESCRIPTION_OUTPUT=1`, `PHOTOS_INPUT=2`, `RETOUCH_PREVIEW=3`, `PHOTOS_OUTPUT=4`, `OCR=5`, `ALLEGRO_FORM=6`). Every tab-dependent branch routes through them, which is why reordering tabs is a small change — keep it that way, never compare raw indices.
- *Retouch Preview* shows one of the offer's photos before/after the ticked retouching steps (a `[|<] [< Prev] 1/20 [Next >] [>|]` stepper picks which), rendered by `core/RetouchPreview` — the pipeline's own `Retouch`/`AutoCrop` code chained in memory, so the preview cannot drift from a run. Its four checkboxes are the Workflow section's, mirrored (`linkRetouchBoxes`); `setSelected` doesn't fire an `ActionListener`, which is why the mirror can't loop. The two `StrengthDial` sliders (brightness, contrast) have no twins: they live only here, and `currentConfig()` overrides `BRIGHTNESS_STRENGTH` / `CONTRAST_STRENGTH` with them so a run reproduces what the preview showed. The preview decodes *subsampled* to display size (`ImageReadParam.setSourceSubsampling`) — ~0.15s instead of ~1.2s of retouching, and legitimate because every retouch op is global (means over the frame); a run still works at full size. It renders on its own thread, only while the tab is visible (`previewStale` defers it otherwise), and only once a slider settles (`getValueIsAdjusting`, plus a one-shot timer for the mouse wheel, which has no adjusting flag).
- A `CardLayout` (`CARD_EDITOR` / `CARD_PHOTOS`) swaps the bottom button bar per tab; `updateBottomBar()` must run after the cards are added.
- `outputPhotoDir(offerDir)` is the single source of truth for "the finished photos" (`cropped/` if present, else `retouched/`). The gallery and the *Open photo dir* button both call it so they cannot drift.
- *Copy all to Allegro* (Allegro Lokalnie Form tab) fills the live listing form through `core/AllegroForm` + `core/Cdp` — a hand-rolled Chrome DevTools Protocol client on the JDK's own WebSocket (no library; Chrome is an external program like tesseract). Photos go in via `DOM.setFileInputFiles`, the title via the native value setter + `input` event, the description is *typed* (`Input.insertText` + Enter keystrokes) because it's a ProseMirror editor that ignores DOM mutation. It fills, never submits. The selectors are Allegro's `data-testid` hooks — if the fill breaks, diff those against the live page first.
- A grid row resolves to an offer dir by matching `name` in each `data.json`, falling back to the row's position among the sorted dirs (`resolveOfferDir`).
- `ColorEmoji` parses the `CBDT`/`CBLC` bitmap tables out of Noto Color Emoji and paints those images, because **Java2D cannot rasterize color fonts**. It restyles the view only — the document text keeps the original emoji characters, so saving round-trips them. Absent the font, it degrades to monochrome glyphs.
- Thumbnails decode subsampled and load off the UI thread on a single-threaded executor.

## Configuration

`Config.forBaseDir(baseDir)` derives every path from the base directory, overridable by env var or a `.env` file in it. **A real environment variable wins over `.env`.** Keys: `CSV_PATH`, `RAW_PHOTOS_DIR`, `OFFERS_DIR`, `MTP_UID`, `MTP_GLOB_PATTERN`, `PHOTOS_PER_OFFER`, `SERIES_GAP_THRESHOLD_SECONDS`, `SERIES_RECOGNITION` (`auto` | `single` | `subfolders`), `BRIGHTNESS_STRENGTH`, `CONTRAST_STRENGTH`, `OCR_LANGUAGES`, `OPENAI_API_KEY`, `OPENAI_MODEL`, `OPENAI_BASE_URL`, `OPENAI_SYSTEM_PROMPT`, `OPENAI_USER_PROMPT`, `CHROME_BIN`, `CHROME_PROFILE_DIR`.

The OpenAI keys are editable in File > Settings > OpenAI API, which writes them back to `.env` via `Config.updateDotenv` (only values differing from the built-in defaults; multi-line prompts are stored double-quoted with `\n` escapes). The prompt defaults are `GenerateDescription.SYSTEM_PROMPT` / `USER_PROMPT`; the user prompt is a template whose `{{OFFER_JSON}}` placeholder is replaced with the offer JSON.

`offers.csv` is **tab-delimited** on write; on read the delimiter is auto-detected (tab if the header has one, else comma).

## Conventions

- Code, comments, docs, and log output are **English**. The OpenAI system prompt and the generated offer text stay **Polish** — Allegro Lokalnie is a Polish marketplace. Don't "fix" the Polish strings in `GenerateDescription`.
- UI strings are **English literals in the code**; Polish (File > Settings > Language) comes from `ui/I18n` — `I18n.t(...)` at sites that compose text at runtime, and a `retranslate` tree-walk that swaps build-time texts on live components by exact match. New user-visible UI text therefore needs an entry in `I18n`'s map (Polish values must stay unique — the reverse map normalizes displayed texts back to English before re-translating). The pipeline log is deliberately untranslated.
- Commit **directly to `main`**; no feature branches. Commit subjects are imperative and sentence-cased ("Add an Auto-crop workflow step").
- Class javadocs here carry the *why* — the non-obvious rationale (why Java2D can't do color emoji, why brightness thresholding fails on a white item). When you change such a mechanism, update its javadoc; it's the design doc.
