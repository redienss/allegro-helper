# Allegro Helper (desktop app)

A Java desktop application that assists with preparing offers for **Allegro
Lokalnie**. It is a GUI front-end for the same pipeline as the Python CLI in
`../allegro_bot`: import photos from the phone, match them to CSV rows, retouch
them, and generate descriptions.

It is a *helper*, not a bot — it does not click on Allegro. Publishing the
listing stays manual.

## What it does

The window is split into two equal-width halves: the **left** holds all the
controls (below); the **right** shows the offer selected in the grid (resolved
by matching the row's name to each offer's `data.json`, falling back to row
position) in two editable tabs. A bottom bar has **Delete** and **Clear** in
the lower-left corner (away from **Save** in the lower-right, to avoid
accidental clicks), all acting on the active tab's file:

- **Save** writes the editor to the file.
- **Clear** empties the editor only — the file is unchanged until you Save.
- **Delete** removes the file from disk (after a confirmation dialog) and
  clears the editor.

The two tabs:

- **More Data (Input)** ↔ `more_data_<N>.txt` next to `offers.csv` (N = the
  row's 1-based number) — extra free-form notes folded into the description by
  the Describe step. Editable/saveable even before Match.
- **Offer Details (Output)** ↔ `description.txt` in the offer directory — the
  generated description; edit and save to tweak it. Available after Match.

The left side has the sections from the spec:

- **Photos** — photo series detected on the connected phone (before import),
  e.g. `20260708_0340 | 20x series of photos to import`. Click **Refresh** to
  scan.
- **Offer Data** — an editable grid (`Name | Brand | Model | Condition | Damage
  | Quantity | Price | InPost Size`). Loaded from `offers.csv` in the base
  directory if present; otherwise empty and fillable by hand. You can also
  **Load CSV…** from anywhere, **Save CSV**, and add/remove rows.
- **Workflow** — checkboxes `Import`, `Match`, `Retouch`, `Describe` (all
  checked by default).
- **Start** — runs the selected steps in order. If `Match` is selected, the
  grid is first written to `offers.csv` (the match step's input).
- **Progress** — overall progress across the selected steps.
- **Log** — the same log output as the CLI (`== import ==`, `== match ==`, …).

The processing logic mirrors `allegro_bot/` exactly: the same photo clustering,
`data.json` layout, gray-world white balance + auto-contrast (with EXIF
orientation), and the same (Polish) OpenAI prompt. This was verified against the
Python implementation — the retouched pixels and the API request payload are
byte-identical.

## Requirements

- A JDK (Java 17+; developed and tested on Java 25). No Maven/Gradle and **no
  external dependencies** — only the JDK standard library (Swing, `java.net.http`,
  `javax.imageio`).
- `gio` (GVFS) for reading photos from a phone mounted via MTP (same as the
  Python tool).
- An OpenAI API key for the *Describe* step (`OPENAI_API_KEY`), read from the
  environment or a `.env` file in the base directory — the same `.env` the
  Python tool uses.

## Build & run

```bash
cd app
./build.sh        # compiles to build/classes (and build/allegro-helper.jar if the `jar` tool is present)
./run.sh          # launches the desktop UI
```

`build.sh` finds `javac` on `PATH`, then `$JAVA_HOME`, then common JVM install
locations; override with `JAVAC=/path/to/javac ./build.sh` if needed.

### Headless / scripting

The same pipeline can run without a UI, mirroring `python main.py <step>`:

```bash
./run.sh --cli import      # or match | retouch | describe | all
./run.sh --cli all /path/to/base-dir
```

## Configuration

The base directory (chosen at the top of the window, default: the launch
directory) determines `offers.csv`, `raw_photos/`, and `offers/`. All of the
Python tool's environment variables are honored (`CSV_PATH`, `RAW_PHOTOS_DIR`,
`OFFERS_DIR`, `MTP_GLOB_PATTERN`, `PHOTOS_PER_OFFER`,
`SERIES_GAP_THRESHOLD_SECONDS`, `OPENAI_API_KEY`, `OPENAI_MODEL`), plus an
optional `OPENAI_BASE_URL` for an OpenAI-compatible endpoint. A real environment
variable takes precedence over a value in `.env`.
