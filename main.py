#!/usr/bin/env python3
"""CLI do uruchamiania kroków pipeline'u AllegroBot.

Użycie:
    python main.py import      - kopiuje zdjęcia z telefonu do raw_photos/
    python main.py match       - dopasowuje zdjęcia z raw_photos/ do wierszy oferty.csv
    python main.py retouch     - retusz zdjęć (balans bieli, auto-kontrast)
    python main.py describe    - generuje opis.txt dla każdej oferty (wymaga OPENAI_API_KEY)
    python main.py all         - wykonuje wszystkie kroki po kolei
"""
import argparse
import sys

from allegro_bot import generate_description, group_and_match, import_photos, retouch

STEPS = {
    "import": import_photos.import_photos,
    "match": group_and_match.group_and_match,
    "retouch": retouch.retouch_all,
    "describe": generate_description.generate_all,
}


def main() -> None:
    parser = argparse.ArgumentParser(description="AllegroBot - pipeline przygotowania ofert")
    parser.add_argument("step", choices=[*STEPS.keys(), "all"])
    args = parser.parse_args()

    steps_to_run = list(STEPS.items()) if args.step == "all" else [(args.step, STEPS[args.step])]

    for name, func in steps_to_run:
        print(f"== {name} ==")
        func()


if __name__ == "__main__":
    try:
        main()
    except SystemExit:
        raise
    except Exception as exc:
        print(f"Błąd: {exc}", file=sys.stderr)
        sys.exit(1)
