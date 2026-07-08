"""Generowanie opisu oferty (opis.txt) na podstawie dane.json, z użyciem OpenAI API.

Opis jest generowany WYŁĄCZNIE na podstawie danych podanych w oferty.csv
(marka, model, stan, uszkodzenia, ilość, gabaryt) - model ma nie dopisywać
własnych, niepotwierdzonych cech przedmiotu.
"""
import json
import sys
from pathlib import Path

from openai import OpenAI

from allegro_bot import config

SYSTEM_PROMPT = (
    "Piszesz krótkie, rzeczowe opisy ofert sprzedaży używanych przedmiotów prywatnych "
    "na Allegro Lokalnie, w języku polskim. Używaj WYŁĄCZNIE informacji podanych przez "
    "użytkownika - nie zmyślaj cech, historii ani stanu przedmiotu, których nie podano. "
    "Pole 'Stan' przepisz dokładnie tak, jak zostało podane - nie dodawaj własnej oceny "
    "jakości (np. 'w bardzo dobrym stanie', 'świetny stan'), jeśli takiej oceny nie ma "
    "w danych wejściowych. Styl: konkretny, bez marketingowego zachwytu, 3-5 zdań. Jeśli "
    "podano uszkodzenia inne niż 'brak', wymień je wprost. Jeśli ilość sztuk > 1, zaznacz "
    "to w opisie."
)


def build_user_prompt(dane: dict) -> str:
    lines = [
        f"Nazwa: {dane.get('nazwa', '')}",
        f"Marka: {dane.get('marka', '')}",
        f"Model: {dane.get('model', '')}",
        f"Stan: {dane.get('stan', '')}",
        f"Uszkodzenia: {dane.get('uszkodzenia', '')}",
        f"Ilość sztuk: {dane.get('ilość', '')}",
        f"Gabaryt InPost: {dane.get('gabaryt InPost', '')}",
    ]
    return "Napisz opis oferty na podstawie tych danych:\n" + "\n".join(lines)


def compute_price_range(cena: float) -> tuple[int, int]:
    margin = cena * config.PRICE_RANGE_PERCENT / 100
    return round(cena - margin), round(cena + margin)


def generate_for_offer(client: OpenAI, offer_dir: Path) -> None:
    dane_path = offer_dir / "dane.json"
    opis_path = offer_dir / "opis.txt"

    if not dane_path.exists():
        return
    if opis_path.exists():
        print(f"{offer_dir.name}: opis.txt już istnieje, pomijam.")
        return

    with open(dane_path, encoding="utf-8") as f:
        dane = json.load(f)

    response = client.chat.completions.create(
        model=config.OPENAI_MODEL,
        messages=[
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": build_user_prompt(dane)},
        ],
        temperature=0.4,
    )
    opis_text = response.choices[0].message.content.strip()

    cena = float(dane.get("cena", 0))
    cena_min, cena_max = compute_price_range(cena)

    content = (
        f"{opis_text}\n\n"
        "---\n"
        f"Cena: {cena:.0f} zł\n"
        f"Widełki cenowe: {cena_min}-{cena_max} zł\n"
        f"Gabaryt InPost: {dane.get('gabaryt InPost', '')}\n"
    )

    opis_path.write_text(content, encoding="utf-8")
    print(f"{offer_dir.name}: wygenerowano opis.txt.")


def generate_all() -> None:
    if not config.OPENAI_API_KEY:
        print(
            "Brak OPENAI_API_KEY. Ustaw go w pliku .env (skopiuj .env.example) "
            "przed uruchomieniem tego kroku.",
            file=sys.stderr,
        )
        raise SystemExit(1)

    if not config.OFFERS_DIR.exists():
        print(f"Katalog {config.OFFERS_DIR} nie istnieje, brak ofert do opisania.")
        return

    client = OpenAI(api_key=config.OPENAI_API_KEY)
    for offer_dir in sorted(config.OFFERS_DIR.iterdir()):
        if offer_dir.is_dir():
            generate_for_offer(client, offer_dir)


if __name__ == "__main__":
    generate_all()
