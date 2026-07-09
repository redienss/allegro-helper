# Allegro Helper

<p align="center">
  <img src="logo/logo.png" alt="Allegro Helper logo" width="320">
</p>

A Java desktop application that helps sell off private items on **Allegro
Lokalnie**. It automates the workflow from taking photos to writing offer
descriptions, as far as Allegro's rules allow.

It is a *helper*, not a bot — it does not click on Allegro. Publishing the
listing stays manual.

The project itself (code, docs, log output) is in English. The OpenAI prompt and
the generated offer text stay in Polish, since Allegro Lokalnie is a Polish
marketplace.

## The workflow

1. Item photos are taken on a rotating turntable, controlled by a remote, with a
   phone on a tripod running **OpenCamera** — a series of 20 photos, one every
   5 seconds.
2. **Import** — photos are copied from the phone (mounted via MTP) into
   `raw_photos/`. The originals stay on the phone.
3. **Match** — photos are grouped into series by the timestamp in their filename
   and matched to the rows of `offers.csv`, one series per row, in order. Each
   series is moved into its own directory named after its first photo,
   e.g. `offers/20260708_0340/`.
4. **Retouch** — automatic gray-world white balance and auto-contrast.
5. **Auto-crop** — each series is cropped to the item. Because the item rotates
   on the turntable while the background and table stay still, the item is
   simply the part of the frame that changes across the series. That is what the
   step measures, so it works even for a white item on a light background, where
   brightness thresholding and edge detection both fail. One crop box is used
   for the whole series (the item does not jump between frames), grown by a
   small margin and kept at the source aspect ratio. Results go to `cropped/`;
   `retouched/` is left untouched.
6. **Describe** — an offer description is generated per offer via the OpenAI
   API, including the price taken directly from the CSV.
7. The offer is then created on Allegro Lokalnie by hand, using the cropped
   photos and the generated description.

Every step is safe to re-run — already processed offers and photos are skipped.

Auto-crop is deliberately cautious. If the series is too short to show movement,
the detected item is implausibly small, or the crop would keep nearly the whole
frame, it says so in the log and leaves that offer uncropped rather than
cropping it wrongly. It reports the box it settled on:

```
== auto-crop ==
20260708_0340: cropped 20 photos to 2225x1669.
20260708_0349: cropped 20 photos to 2210x1658.
```

### What stays manual

- Shooting the photos (the turntable and remote are only semi-automatic).
- Filling in `offers.csv` (e.g. the item model), so a sensible description can
  be generated.
- Publishing the offer on Allegro Lokalnie.

## The window

The window is split into two equal-width halves: controls on the left, the
selected offer on the right.

![The main window, on the Description (Input) tab](screenshots/001.png)

### Left: controls

- **Photos** — photo series detected on the connected phone (before import),
  e.g. `20260708_0340 | 20x series of photos to import`. Scanned automatically
  on launch; click **Refresh** to rescan.
- **Offer Data** — an editable grid (`Name | Brand | Model | Condition | Damage |
  Quantity | Price | InPost Size`). Loaded from `offers.csv` in the base
  directory if present; otherwise empty and fillable by hand. You can also
  **Load CSV…** from anywhere, **Save CSV**, and add/remove rows.
- **Workflow** — checkboxes `Import`, `Match`, `Retouch`, `Auto-crop`,
  `Describe` (all checked by default).
- **Start** — runs the selected steps in order. If `Match` is selected, the grid
  is written to `offers.csv` first (that step's input).
- **Progress** — overall progress across the selected steps.
- **Log** — the run log (`== import ==`, `== match ==`, …).

### Right: the selected offer

Clicking a row in the grid shows that offer (resolved by matching the row's name
to each offer's `data.json`, falling back to row position) in four tabs:

- **Description (Input)** — editor for `more_data_<N>.txt` next to `offers.csv`
  (N = the row's 1-based number): extra free-form notes folded into the
  description by the Describe step. Editable and saveable even before Match.
- **Description (Output)** — editor for `description.txt` in the offer
  directory: the generated description; edit and save to tweak it.
- **Photos (Input)** — thumbnail gallery of the original photos
  (`offers/<id>/photos/`).
- **Photos (Output)** — thumbnail gallery of the finished photos: the
  auto-cropped ones (`offers/<id>/cropped/`) once that step has run, otherwise
  the merely retouched ones (`offers/<id>/retouched/`).

![The Description (Output) tab, showing a generated description](screenshots/002.png)

Thumbnails load upright (EXIF-corrected) off the UI thread; double-click one to
open it in the system image viewer. The Photos tabs have an **Open photo dir**
button in the lower-right.

![The Photos (Input) tab, showing the original photos](screenshots/003.png)

Comparing the two galleries shows what Auto-crop did: the same series, framed to
the item.

![The Photos (Output) tab, showing the auto-cropped photos](screenshots/004.png)

The Description tabs have a bottom bar with **Delete** and **Clear** in the
lower-left corner (away from **Save** in the lower-right, to avoid accidental
clicks), all acting on the active tab's file:

- **Save** writes the editor to the file.
- **Clear** empties the editor only — the file is unchanged until you Save.
- **Delete** removes the file from disk (after a confirmation dialog) and clears
  the editor.

Emoji in the descriptions are drawn as color images. Java2D cannot rasterize
color fonts, so the app reads the PNG bitmaps embedded in Noto Color Emoji's
`CBDT`/`CBLC` tables and paints those instead of the monochrome glyph. The
document text is untouched, so saving still writes the original emoji
characters. Without a color emoji font the text falls back to the normal glyphs
(set `ALLEGRO_EMOJI_FONT` to point at one explicitly).

## Requirements

- A JDK (Java 17+; developed and tested on Java 25). No Maven/Gradle and **no
  external dependencies** — only the JDK standard library (Swing,
  `java.net.http`, `javax.imageio`).
- `gio` (GVFS) to read photos from a phone connected via MTP (mounted at
  `/run/user/<uid>/gvfs/mtp:host=...`).
- An OpenAI API key for the *Describe* step (`OPENAI_API_KEY`), read from the
  environment or a `.env` file in the base directory (see `.env.example`).

## Build & run

```bash
./build.sh        # compiles to build/classes (and build/allegro-helper.jar if the `jar` tool is present)
./run.sh          # launches the desktop UI
```

`build.sh` finds `javac` on `PATH`, then `$JAVA_HOME`, then common JVM install
locations; override with `JAVAC=/path/to/javac ./build.sh` if needed.

### Desktop shortcut

The window/taskbar icon is the app icon
(`icons/AllegroHelper-icon-full-logo-1024.png`), bundled onto the classpath at
build time. To add a launcher to the application menu and Desktop:

```bash
./install-desktop-entry.sh
```

This installs `~/.local/share/applications/allegro-helper.desktop` (plus a copy
on the Desktop) and the icon under the hicolor theme. The launcher sets
`StartupWMClass=AllegroHelper`, which the app advertises as its X11 WM class, so
GNOME shows this icon for the running window too.

### Headless / scripting

The same pipeline runs without a UI:

```bash
./run.sh --cli import      # or match | retouch | autocrop | describe | all
./run.sh --cli all /path/to/base-dir
```

## Configuration

The base directory (chosen at the top of the window, default: the launch
directory) determines `offers.csv`, `raw_photos/` and `offers/`. These
environment variables are honored, from the environment or `.env`:
`CSV_PATH`, `RAW_PHOTOS_DIR`, `OFFERS_DIR`, `MTP_GLOB_PATTERN`,
`PHOTOS_PER_OFFER`, `SERIES_GAP_THRESHOLD_SECONDS`, `OPENAI_API_KEY`,
`OPENAI_MODEL`, and `OPENAI_BASE_URL` (for an OpenAI-compatible endpoint). A
real environment variable takes precedence over a value in `.env`.

## Data layout

### `offers.csv`

Tab-delimited. Rows must be in the same order in which the photo series were
taken. Values stay in Polish, since that's the language of the actual listings.

| column | description | example |
|---|---|---|
| name | full offer name | Ładowarka ścienna Poss PSWCC-2.4WH-18 typu C – biała |
| brand | product brand | Poss |
| model | product model | PSWCC-2.4WH-18 |
| condition | item condition | używany |
| damage | description of damage/defects | brak |
| quantity | number of units | 1 |
| price | price in PLN | 10 |
| inpost_size | InPost locker size | A |

### Optional extra notes (`more_data_<N>.txt`)

For information too long or unstructured for a CSV column (test results, usage
history, what's included, …), place `more_data_1.txt`, `more_data_2.txt`, … next
to `offers.csv` — the number matches the 1-based row order. During **Match**
each is copied into the matching offer directory as `more_data.txt`, and
**Describe** passes it to OpenAI as truthful, item-specific notes to be folded
into the description (reformatted or shortened, never embellished with invented
details).

### Source photos

Phone (Samsung, MTP):
`mtp://SAMSUNG_SAMSUNG_Android_R58R301MAHN/Internal storage/DCIM/OpenCamera`

Filenames follow the OpenCamera convention `IMG_YYYYMMDD_HHMMSS.jpg`. Photos
within one series are ~5 seconds apart, with a noticeably longer gap between
series (time to swap the item on the turntable) — which is how series boundaries
are detected.

### Output

For each offer a directory `offers/<YYYYMMDD_HHMM>/` is created (timestamp of
the series' first photo):

```
offers/20260708_0340/
  data.json        # data from the CSV row + list of photos
  photos/          # original photos of the series
  retouched/       # photos after auto white balance and auto-contrast
  cropped/         # retouched photos cropped to the item (Auto-crop)
  more_data.txt    # optional, copied from more_data_<N>.txt if present
  description.txt  # generated description + price (from the CSV)
```
