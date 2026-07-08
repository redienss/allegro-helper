"""Konfiguracja projektu. Wszystkie wartości można nadpisać zmiennymi środowiskowymi (patrz .env.example)."""
import os
from pathlib import Path

from dotenv import load_dotenv

load_dotenv()

BASE_DIR = Path(__file__).resolve().parent.parent

# Ścieżka do pliku CSV ze zbiorczą listą ofert.
CSV_PATH = Path(os.environ.get("CSV_PATH", BASE_DIR / "oferty.csv"))

# Katalog roboczy na zaimportowane, jeszcze nieprzypisane zdjęcia.
RAW_PHOTOS_DIR = Path(os.environ.get("RAW_PHOTOS_DIR", BASE_DIR / "raw_photos"))

# Katalog, w którym powstają podkatalogi poszczególnych ofert (np. oferty/20260708_0340/).
OFFERS_DIR = Path(os.environ.get("OFFERS_DIR", BASE_DIR / "oferty"))

# Wzorzec glob do wyszukania katalogu DCIM/OpenCamera na zamontowanym telefonie (gvfs-mtp).
# Jeden poziom wildcard obejmuje lokalizowany folder pamięci wewnętrznej (np. "Pamięć wewnętrzna").
MTP_UID = os.environ.get("MTP_UID", str(os.getuid()) if hasattr(os, "getuid") else "1000")
MTP_GLOB_PATTERN = os.environ.get(
    "MTP_GLOB_PATTERN",
    f"/run/user/{MTP_UID}/gvfs/mtp:host=*/*/DCIM/OpenCamera",
)

# Oczekiwana liczba zdjęć w jednej serii (jedna oferta na turntable).
PHOTOS_PER_OFFER = int(os.environ.get("PHOTOS_PER_OFFER", "20"))

# Próg (w sekundach) przerwy między zdjęciami, powyżej którego uznajemy że zaczyna się nowa seria/oferta.
# Zdjęcia w obrębie serii są robione co ~5s, przerwa między seriami to zwykle kilka minut.
SERIES_GAP_THRESHOLD_SECONDS = int(os.environ.get("SERIES_GAP_THRESHOLD_SECONDS", "60"))

# Ustawienia generowania opisu.
OPENAI_API_KEY = os.environ.get("OPENAI_API_KEY", "")
OPENAI_MODEL = os.environ.get("OPENAI_MODEL", "gpt-4o-mini")

# Widełki cenowe: +/- ten procent ceny bazowej z CSV.
PRICE_RANGE_PERCENT = float(os.environ.get("PRICE_RANGE_PERCENT", "15"))
