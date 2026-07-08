# AllegroBot

A tool that helps sell off private items on **Allegro Lokalnie**. The goal of the project is to partially automate the workflow from taking photos to writing offer descriptions — as much as possible while staying compliant with Allegro's rules (actually publishing the listing remains manual).

The project itself (code, docs, CLI output) is in English. The OpenAI prompt and the generated offer text stay in Polish, since Allegro Lokalnie is a Polish marketplace (listing descriptions, titles, communication with buyers).

## Current workflow (manual)

1. Item photos are taken on a rotating turntable, controlled by a remote, with a phone on a tripod running **OpenCamera** — a series of 20 photos, one every 5 seconds.
2. Photos are transferred from the phone to the computer.
   Location on the phone: `mtp://SAMSUNG_SAMSUNG_Android_R58R301MAHN/Internal storage/DCIM/OpenCamera`
3. Photos are touched up (white balance, brightness, contrast) to keep good brightness without blowing out details.
4. Basic item information is written down: model, quantity, condition, extra info, parcel size (InPost size), price.
5. An offer description is written/generated.
6. The offer is manually created on Allegro Lokalnie using the photos and description.

## Steps to automate

1. **Read the CSV** with the batch list of offers to prepare (file `offers.csv`, columns: `name | brand | model | condition | damage | quantity | price | inpost_size`).
2. **Import photos** from the phone (MTP location as above) into the project's working directory.
3. **Match photos to CSV rows** — e.g. with 10 rows in `offers.csv` there should be 10 × 20 photos in the same order as the rows in the file.
4. **Split into offer directories** — each photo series goes into its own directory named after the date and time of the first photo in the series, e.g. `20260708_0316`.
5. **Retouch photos** — automatic white balance and contrast/brightness correction (gray-world + auto-contrast). Cropping remains manual for now — the risk of incorrectly auto-cropping the item across different shapes is too high.
6. **Generate the offer description** — a text file per offer, e.g. `20260708_0316/description.txt`.
7. **Pricing** — a suggested price range saved alongside the description.

## Steps that remain manual

1. **Photos** — the turntable and tripod are semi-automatic (remote control), but the series still requires attendance and operation.
2. **Preparing the CSV file** — must be filled in by hand (e.g. item model), so that a sensible description can be generated.
3. **Publishing the offer on Allegro Lokalnie** — still manual for now, based on the generated photos and description.

## Input data structure

### `offers.csv`

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

Rows in the file must be in the same order in which the photo series were taken. Row values themselves stay in Polish, since that's the language of the actual listings.

### Source photos

Phone (Samsung, MTP): `mtp://SAMSUNG_SAMSUNG_Android_R58R301MAHN/Internal storage/DCIM/OpenCamera`

File name per OpenCamera convention: `IMG_YYYYMMDD_HHMMSS.jpg`. Photos within a single series (one offer) are ~5 seconds apart; there's a noticeably longer gap between series (time needed to swap the item on the turntable) — this makes it possible to automatically detect series boundaries.

## Output data structure

For each offer a directory `offers/<YYYYMMDD_HHMM>/` is created (timestamp of the series' first photo):

```
offers/20260708_0340/
  data.json      # data from the CSV row + list of photos
  photos/        # original photos of the series
  retouched/     # photos after auto white balance and auto-contrast
  description.txt  # generated description + price/price range
```

## Technical requirements

- Python 3 (environment: `python3 -m venv .venv && .venv/bin/pip install -r requirements.txt`).
- Pillow — photo processing.
- `gio` (GVFS) to read files from the phone connected via MTP (mounted at `/run/user/<uid>/gvfs/mtp:host=...`).
- OpenAI API key (`OPENAI_API_KEY` variable in the `.env` file, see `.env.example`) — only needed for generating descriptions.

## Installation and usage

```bash
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
cp .env.example .env   # then fill in OPENAI_API_KEY

.venv/bin/python main.py import    # copies photos from the phone to raw_photos/ (originals stay on the phone)
.venv/bin/python main.py match     # matches photos to rows in offers.csv, creates directories in offers/
.venv/bin/python main.py retouch   # auto white balance + auto-contrast for each offer
.venv/bin/python main.py describe  # generates description.txt (requires OPENAI_API_KEY)

# or all at once:
.venv/bin/python main.py all
```

Every step is safe to run repeatedly — already processed offers/photos are skipped.
