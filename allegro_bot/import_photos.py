"""Import zdjęć z telefonu (zamontowanego przez gvfs-mtp) do lokalnego katalogu roboczego.

Pliki są tylko kopiowane - oryginały na telefonie pozostają nietknięte.
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
            "Nie znaleziono katalogu DCIM/OpenCamera na telefonie.\n"
            f"Szukano wzorca: {config.MTP_GLOB_PATTERN}\n"
            "Upewnij się, że telefon jest podłączony i zamontowany (sprawdź: gio mount -l)."
        )
    if len(matches) > 1:
        print(f"Uwaga: znaleziono więcej niż jedno pasujące urządzenie, używam pierwszego: {matches[0]}")
    return Path(matches[0])


def import_photos() -> None:
    source_dir = find_source_dir()
    config.RAW_PHOTOS_DIR.mkdir(parents=True, exist_ok=True)

    photos = sorted(
        p for p in source_dir.iterdir() if p.suffix.lower() in (".jpg", ".jpeg")
    )
    if not photos:
        print(f"Brak zdjęć do zaimportowania w {source_dir}.")
        return

    copied, skipped, failed = 0, 0, 0
    for src in photos:
        dest = config.RAW_PHOTOS_DIR / src.name
        try:
            if not dest.exists():
                shutil.copy2(src, dest)
                if dest.stat().st_size != src.stat().st_size:
                    dest.unlink(missing_ok=True)
                    raise IOError("rozmiar skopiowanego pliku nie zgadza się z oryginałem")
                copied += 1
            else:
                skipped += 1
        except Exception as exc:
            failed += 1
            print(f"Błąd przy imporcie {src.name}: {exc}", file=sys.stderr)

    print(
        f"Import zakończony: skopiowano {copied}, pominięto (już istniały) {skipped}, "
        f"błędów {failed}. Oryginały pozostały na telefonie."
    )


if __name__ == "__main__":
    import_photos()
