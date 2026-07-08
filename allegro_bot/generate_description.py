"""Generate the offer description (description.txt) from data.json, using the OpenAI API.

Item-specific facts (condition, damage, quantity) come EXCLUSIVELY from the
data in offers.csv - the model must not invent or embellish those. It may,
however, supplement the description with general, well-known technical
specs of the product (e.g. wattage, connector type, capacity) inferred from
brand/model, as long as it's confident those specs are correct for that
exact model rather than guessing. The price is taken directly from the CSV,
with no computed price range.

If the offer directory contains a more_data.txt file (see group_and_match.py),
its free-form content is passed to the model as additional, truthful notes
about that specific item, to be reformatted/edited into the description
rather than taken as an invitation to invent further details.

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
    "na Allegro Lokalnie, w języku polskim.\n\n"
    "Dane o KONKRETNYM egzemplarzu (stan, uszkodzenia, ilość) pochodzą WYŁĄCZNIE od "
    "użytkownika - nie zmyślaj ani nie upiększaj tych informacji. Pole 'Stan' przepisz "
    "dokładnie tak, jak zostało podane - nie dodawaj własnej oceny jakości (np. 'w bardzo "
    "dobrym stanie', 'świetny stan'), jeśli takiej oceny nie ma w danych wejściowych. Jeśli "
    "podano uszkodzenia inne niż 'brak', wymień je wprost. Jeśli ilość sztuk > 1, zaznacz to "
    "w opisie.\n\n"
    "Dodatkowo możesz uzupełnić opis o OGÓLNE, oficjalne dane techniczne produktu (np. moc, "
    "napięcie, rodzaj złącza, pojemność, wymiary, kompatybilność), jeśli na podstawie marki "
    "i modelu jesteś w stanie z dużą pewnością wskazać te dane - czyli są one publicznie znane "
    "dla tego dokładnego modelu, a nie zgadywane. Jeśli nie masz pewności co do konkretnej "
    "specyfikacji, po prostu ją pomiń - nie pisz zdań w stylu 'wymiary nie zostały podane' ani "
    "innych wzmianek o brakujących danych. Te dane dotyczą produktu jako takiego, a nie stanu "
    "czy historii tego konkretnego egzemplarza - nie myl ich z polem 'Stan'.\n\n"
    "Czasem otrzymasz też pole 'Dodatkowe informacje' z surową notatką o tym produkcie - "
    "może to być np. wynik testu, historia użytkowania, zawartość zestawu, specyfikacja "
    "techniczna, ale może to też być skopiowany fragment strony sklepu/producenta. Wykorzystaj "
    "z niej tylko fakty o samym produkcie, które są przydatne kupującemu (specyfikacja, "
    "zawartość zestawu, kompatybilność, wyniki testów) i przeredaguj je do stylu reszty opisu. "
    "Pomiń w niej wszystko, co nie jest przydatne dla kupującego lub nie dotyczy tego "
    "konkretnego, sprzedawanego egzemplarza: reklamy innych produktów, politykę zwrotów/"
    "gwarancji sklepu, dane kontaktowe, adres producenta, numery katalogowe/EAN oraz ogólne "
    "instrukcje bezpieczeństwa i obsługi (np. ostrzeżenia z instrukcji użytkownika). "
    "Jeśli notatka zawiera stwierdzenie o stanie produktu (np. 'stan: nowy'), które jest "
    "sprzeczne z polem 'Stan' podanym wyżej - zignoruj je; pole 'Stan' z danych wejściowych "
    "zawsze ma pierwszeństwo, bo dotyczy faktycznie sprzedawanego egzemplarza, a nie ogólnej "
    "oferty ze strony źródłowej.\n\n"
    "Styl: konkretny, rzeczowy, bez marketingowego zachwytu - unikaj sformułowań typu 'idealny "
    "do', 'świetny wybór', 'gwarantuje najwyższą jakość', również przy opisywaniu specyfikacji "
    "technicznych. 3-6 zdań."
)


def build_user_prompt(data: dict, extra_notes: str = "") -> str:
    lines = [
        f"Nazwa: {data.get('name', '')}",
        f"Marka: {data.get('brand', '')}",
        f"Model: {data.get('model', '')}",
        f"Stan: {data.get('condition', '')}",
        f"Uszkodzenia: {data.get('damage', '')}",
        f"Ilość sztuk: {data.get('quantity', '')}",
        f"Gabaryt InPost: {data.get('inpost_size', '')}",
    ]
    prompt = "Napisz opis oferty na podstawie tych danych:\n" + "\n".join(lines)
    if extra_notes:
        prompt += f"\n\nDodatkowe informacje:\n{extra_notes}"
    return prompt


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

    more_data_path = offer_dir / "more_data.txt"
    extra_notes = more_data_path.read_text(encoding="utf-8").strip() if more_data_path.exists() else ""

    response = client.chat.completions.create(
        model=config.OPENAI_MODEL,
        messages=[
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": build_user_prompt(data, extra_notes)},
        ],
        temperature=0.4,
    )
    description_text = response.choices[0].message.content.strip()

    price = float(data.get("price", 0))

    content = (
        f"{description_text}\n\n"
        "---\n"
        f"Cena: {price:.0f} zł\n"
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
