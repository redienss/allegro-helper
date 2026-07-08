#!/usr/bin/env python3
"""CLI for running the AllegroBot pipeline steps.

Usage:
    python main.py import      - copies photos from the phone to raw_photos/
    python main.py match       - matches photos from raw_photos/ to rows in offers.csv
    python main.py retouch     - retouches photos (white balance, auto-contrast)
    python main.py describe    - generates description.txt for each offer (requires OPENAI_API_KEY)
    python main.py all         - runs all steps in sequence
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
    parser = argparse.ArgumentParser(description="AllegroBot - offer preparation pipeline")
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
        print(f"Error: {exc}", file=sys.stderr)
        sys.exit(1)
