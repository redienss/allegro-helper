"""Project configuration. Every value can be overridden via environment variables (see .env.example)."""
import os
from pathlib import Path

from dotenv import load_dotenv

load_dotenv()

BASE_DIR = Path(__file__).resolve().parent.parent

# Path to the CSV file with the batch list of offers.
CSV_PATH = Path(os.environ.get("CSV_PATH", BASE_DIR / "offers.csv"))

# Working directory for imported photos that haven't been matched to an offer yet.
RAW_PHOTOS_DIR = Path(os.environ.get("RAW_PHOTOS_DIR", BASE_DIR / "raw_photos"))

# Directory where per-offer subdirectories are created (e.g. offers/20260708_0340/).
OFFERS_DIR = Path(os.environ.get("OFFERS_DIR", BASE_DIR / "offers"))

# Glob pattern used to locate the DCIM/OpenCamera directory on the mounted phone (gvfs-mtp).
# The single wildcard level covers the internal storage folder name (e.g. "Internal storage").
MTP_UID = os.environ.get("MTP_UID", str(os.getuid()) if hasattr(os, "getuid") else "1000")
MTP_GLOB_PATTERN = os.environ.get(
    "MTP_GLOB_PATTERN",
    f"/run/user/{MTP_UID}/gvfs/mtp:host=*/*/DCIM/OpenCamera",
)

# Expected number of photos in a single series (one offer on the turntable).
PHOTOS_PER_OFFER = int(os.environ.get("PHOTOS_PER_OFFER", "20"))

# Gap (in seconds) between photos above which we consider a new series/offer to have started.
# Photos within a series are taken every ~5s; the gap between series is usually several minutes.
SERIES_GAP_THRESHOLD_SECONDS = int(os.environ.get("SERIES_GAP_THRESHOLD_SECONDS", "60"))

# Description generation settings.
OPENAI_API_KEY = os.environ.get("OPENAI_API_KEY", "")
OPENAI_MODEL = os.environ.get("OPENAI_MODEL", "gpt-4o-mini")

# Suggested price range: +/- this percentage of the base price from the CSV.
PRICE_RANGE_PERCENT = float(os.environ.get("PRICE_RANGE_PERCENT", "15"))
