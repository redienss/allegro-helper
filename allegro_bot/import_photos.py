"""Import photos from the phone (mounted via gvfs-mtp) into a local working directory.

Files are only copied - the originals on the phone are left untouched.
"""
import glob
import shutil
import sys
from pathlib import Path

from allegro_bot import config


def find_source_dir() -> Path:
    matches = sorted(glob.glob(config.MTP_GLOB_PATTERN))
    if not matches:
        raise FileNotFoundError(
            "Could not find the DCIM/OpenCamera directory on the phone.\n"
            f"Pattern searched: {config.MTP_GLOB_PATTERN}\n"
            "Make sure the phone is connected and mounted (check with: gio mount -l)."
        )
    if len(matches) > 1:
        print(f"Warning: found more than one matching device, using the first one: {matches[0]}")
    return Path(matches[0])


def import_photos() -> None:
    source_dir = find_source_dir()
    config.RAW_PHOTOS_DIR.mkdir(parents=True, exist_ok=True)

    photos = sorted(
        p for p in source_dir.iterdir() if p.suffix.lower() in (".jpg", ".jpeg")
    )
    if not photos:
        print(f"No photos to import in {source_dir}.")
        return

    copied, skipped, failed = 0, 0, 0
    for src in photos:
        dest = config.RAW_PHOTOS_DIR / src.name
        try:
            if not dest.exists():
                shutil.copy2(src, dest)
                if dest.stat().st_size != src.stat().st_size:
                    dest.unlink(missing_ok=True)
                    raise IOError("copied file size does not match the original")
                copied += 1
            else:
                skipped += 1
        except Exception as exc:
            failed += 1
            print(f"Error importing {src.name}: {exc}", file=sys.stderr)

    print(
        f"Import finished: copied {copied}, skipped (already present) {skipped}, "
        f"failed {failed}. Originals remain on the phone."
    )


if __name__ == "__main__":
    import_photos()
