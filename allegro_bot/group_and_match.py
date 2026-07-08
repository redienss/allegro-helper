"""Dopasowanie zaimportowanych zdjęć (raw_photos/) do wierszy w oferty.csv.

Zdjęcia są grupowane w serie na podstawie znacznika czasu w nazwie pliku
(konwencja OpenCamera: IMG_YYYYMMDD_HHMMSS.jpg). Przerwa czasowa między
zdjęciami większa niż SERIES_GAP_THRESHOLD_SECONDS oznacza początek nowej
serii (czyli nowej oferty na turntable). Liczba wykrytych serii musi się
zgadzać z liczbą wierszy w CSV - w przeciwnym razie przerywamy, żeby nie
przypisać zdjęć do złej oferty.
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
            f"Nazwa pliku {path.name} nie pasuje do wzorca IMG_YYYYMMDD_HHMMSS.jpg"
        )
    return datetime.strptime(match.group(1) + match.group(2), "%Y%m%d%H%M%S")


def load_offers(csv_path: Path) -> list[dict]:
    with open(csv_path, newline="", encoding="utf-8") as f:
        reader = csv.DictReader(f, delimiter="\t")
        rows = [row for row in reader]
    if not rows:
        raise ValueError(f"Plik {csv_path} nie zawiera żadnych wierszy z ofertami.")
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
        print(f"Brak zdjęć w {config.RAW_PHOTOS_DIR} do dopasowania.")
        return

    gap = timedelta(seconds=config.SERIES_GAP_THRESHOLD_SECONDS)
    clusters = cluster_photos(photos, gap)

    if len(clusters) != len(offers):
        print(
            f"Liczba wykrytych serii zdjęć ({len(clusters)}) nie zgadza się z liczbą "
            f"wierszy w {config.CSV_PATH} ({len(offers)}). Nic nie zostało przeniesione.\n"
            "Wykryte serie:",
            file=sys.stderr,
        )
        for i, cluster in enumerate(clusters, start=1):
            start = parse_timestamp(cluster[0])
            end = parse_timestamp(cluster[-1])
            print(f"  seria {i}: {len(cluster)} zdjęć, {start} -> {end}", file=sys.stderr)
        print(
            "Sprawdź SERIES_GAP_THRESHOLD_SECONDS (obecnie "
            f"{config.SERIES_GAP_THRESHOLD_SECONDS}s) albo kolejność/liczbę wierszy w CSV.",
            file=sys.stderr,
        )
        raise SystemExit(1)

    config.OFFERS_DIR.mkdir(parents=True, exist_ok=True)

    for offer, cluster in zip(offers, clusters):
        if len(cluster) != config.PHOTOS_PER_OFFER:
            print(
                f"Uwaga: seria dla oferty '{offer.get('nazwa')}' ma {len(cluster)} zdjęć "
                f"(oczekiwano {config.PHOTOS_PER_OFFER})."
            )

        offer_timestamp = parse_timestamp(cluster[0])
        offer_dir_name = offer_timestamp.strftime("%Y%m%d_%H%M")
        offer_dir = config.OFFERS_DIR / offer_dir_name

        if offer_dir.exists():
            print(f"Katalog {offer_dir} już istnieje, pomijam (uznaję za już przetworzony).")
            continue

        photos_dir = offer_dir / "zdjecia"
        photos_dir.mkdir(parents=True)

        for photo in cluster:
            shutil.move(str(photo), str(photos_dir / photo.name))

        dane = dict(offer)
        dane["liczba_zdjec"] = len(cluster)
        dane["zdjecia"] = [p.name for p in cluster]
        dane["utworzono"] = datetime.now().isoformat(timespec="seconds")

        with open(offer_dir / "dane.json", "w", encoding="utf-8") as f:
            json.dump(dane, f, ensure_ascii=False, indent=2)

        print(f"Utworzono ofertę {offer_dir_name} ({len(cluster)} zdjęć) dla '{offer.get('nazwa')}'.")


if __name__ == "__main__":
    group_and_match()
