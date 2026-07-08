"""Generate the offer description (description.txt) from data.json, using the OpenAI API.

The description is generated EXCLUSIVELY from the data provided in offers.csv
(brand, model, condition, damage, quantity, package size) - the model must not
add its own, unconfirmed characteristics of the item.

The prompt sent to OpenAI and the generated offer text are intentionally kept
in Polish, since the listing is published on Allegro Lokalnie (Polish market).
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


def build_user_prompt(data: dict) -> str:
    lines = [
        f"Nazwa: {data.get('name', '')}",
        f"Marka: {data.get('brand', '')}",
        f"Model: {data.get('model', '')}",
        f"Stan: {data.get('condition', '')}",
        f"Uszkodzenia: {data.get('damage', '')}",
        f"Ilość sztuk: {data.get('quantity', '')}",
        f"Gabaryt InPost: {data.get('inpost_size', '')}",
    ]
    return "Napisz opis oferty na podstawie tych danych:\n" + "\n".join(lines)


def compute_price_range(price: float) -> tuple[int, int]:
    margin = price * config.PRICE_RANGE_PERCENT / 100
    return round(price - margin), round(price + margin)


def generate_for_offer(client: OpenAI, offer_dir: Path) -> None:
    data_path = offer_dir / "data.json"
    description_path = offer_dir / "description.txt"

    if not data_path.exists():
        return
    if description_path.exists():
        print(f"{offer_dir.name}: description.txt already exists, skipping.")
        return

    with open(data_path, encoding="utf-8") as f:
        data = json.load(f)

    response = client.chat.completions.create(
        model=config.OPENAI_MODEL,
        messages=[
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": build_user_prompt(data)},
        ],
        temperature=0.4,
    )
    description_text = response.choices[0].message.content.strip()

    price = float(data.get("price", 0))
    price_min, price_max = compute_price_range(price)

    content = (
        f"{description_text}\n\n"
        "---\n"
        f"Cena: {price:.0f} zł\n"
        f"Widełki cenowe: {price_min}-{price_max} zł\n"
        f"Gabaryt InPost: {data.get('inpost_size', '')}\n"
    )

    description_path.write_text(content, encoding="utf-8")
    print(f"{offer_dir.name}: generated description.txt.")


def generate_all() -> None:
    if not config.OPENAI_API_KEY:
        print(
            "Missing OPENAI_API_KEY. Set it in the .env file (copy .env.example) "
            "before running this step.",
            file=sys.stderr,
        )
        raise SystemExit(1)

    if not config.OFFERS_DIR.exists():
        print(f"Directory {config.OFFERS_DIR} does not exist, no offers to describe.")
        return

    client = OpenAI(api_key=config.OPENAI_API_KEY)
    for offer_dir in sorted(config.OFFERS_DIR.iterdir()):
        if offer_dir.is_dir():
            generate_for_offer(client, offer_dir)


if __name__ == "__main__":
    generate_all()
