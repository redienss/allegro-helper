"""Match imported photos (raw_photos/) to rows in offers.csv.

Photos are grouped into series based on the timestamp encoded in the
filename (OpenCamera convention: IMG_YYYYMMDD_HHMMSS.jpg). A gap between
photos larger than SERIES_GAP_THRESHOLD_SECONDS marks the start of a new
series (i.e. a new offer on the turntable). The number of detected series
must match the number of rows in the CSV - otherwise we abort, to avoid
assigning photos to the wrong offer.
"""
import csv
import json
import re
import shutil
import sys
from datetime import datetime, timedelta
from pathlib import Path

from allegro_bot import config

FILENAME_RE = re.compile(r"IMG_(\d{8})_(\d{6})")


def parse_timestamp(path: Path) -> datetime:
    match = FILENAME_RE.search(path.name)
    if not match:
        raise ValueError(
            f"File name {path.name} does not match the IMG_YYYYMMDD_HHMMSS.jpg pattern"
        )
    return datetime.strptime(match.group(1) + match.group(2), "%Y%m%d%H%M%S")


def load_offers(csv_path: Path) -> list[dict]:
    with open(csv_path, newline="", encoding="utf-8") as f:
        reader = csv.DictReader(f, delimiter="\t")
        rows = [row for row in reader]
    if not rows:
        raise ValueError(f"File {csv_path} contains no offer rows.")
    return rows


def cluster_photos(photos: list[Path], gap_threshold: timedelta) -> list[list[Path]]:
    timestamped = sorted(((parse_timestamp(p), p) for p in photos), key=lambda t: t[0])
    clusters: list[list[Path]] = []
    for ts, path in timestamped:
        if clusters and ts - clusters[-1][-1][0] <= gap_threshold:
            clusters[-1].append((ts, path))
        else:
            clusters.append([(ts, path)])
    return [[path for _, path in cluster] for cluster in clusters]


def group_and_match() -> None:
    offers = load_offers(config.CSV_PATH)
    photos = sorted(p for p in config.RAW_PHOTOS_DIR.iterdir() if p.suffix.lower() in (".jpg", ".jpeg"))

    if not photos:
        print(f"No photos in {config.RAW_PHOTOS_DIR} to match.")
        return

    gap = timedelta(seconds=config.SERIES_GAP_THRESHOLD_SECONDS)
    clusters = cluster_photos(photos, gap)

    if len(clusters) != len(offers):
        print(
            f"The number of detected photo series ({len(clusters)}) does not match the "
            f"number of rows in {config.CSV_PATH} ({len(offers)}). Nothing was moved.\n"
            "Detected series:",
            file=sys.stderr,
        )
        for i, cluster in enumerate(clusters, start=1):
            start = parse_timestamp(cluster[0])
            end = parse_timestamp(cluster[-1])
            print(f"  series {i}: {len(cluster)} photos, {start} -> {end}", file=sys.stderr)
        print(
            "Check SERIES_GAP_THRESHOLD_SECONDS (currently "
            f"{config.SERIES_GAP_THRESHOLD_SECONDS}s) or the order/count of rows in the CSV.",
            file=sys.stderr,
        )
        raise SystemExit(1)

    config.OFFERS_DIR.mkdir(parents=True, exist_ok=True)

    for offer, cluster in zip(offers, clusters):
        if len(cluster) != config.PHOTOS_PER_OFFER:
            print(
                f"Warning: the series for offer '{offer.get('name')}' has {len(cluster)} photos "
                f"(expected {config.PHOTOS_PER_OFFER})."
            )

        offer_timestamp = parse_timestamp(cluster[0])
        offer_dir_name = offer_timestamp.strftime("%Y%m%d_%H%M")
        offer_dir = config.OFFERS_DIR / offer_dir_name

        if offer_dir.exists():
            print(f"Directory {offer_dir} already exists, skipping (assuming already processed).")
            continue

        photos_dir = offer_dir / "photos"
        photos_dir.mkdir(parents=True)

        for photo in cluster:
            shutil.move(str(photo), str(photos_dir / photo.name))

        data = dict(offer)
        data["photo_count"] = len(cluster)
        data["photos"] = [p.name for p in cluster]
        data["created_at"] = datetime.now().isoformat(timespec="seconds")

        with open(offer_dir / "data.json", "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=2)

        print(f"Created offer {offer_dir_name} ({len(cluster)} photos) for '{offer.get('name')}'.")


if __name__ == "__main__":
    group_and_match()
