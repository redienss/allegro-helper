"""Generate the offer description (description.txt) from data.json, using the OpenAI API.

Item-specific facts (condition, damage, quantity) come EXCLUSIVELY from the
data in offers.csv - the model must not invent or embellish those. It may,
however, supplement the description with general, well-known technical
specs of the product (e.g. wattage, connector type, capacity) inferred from
brand/model, as long as it's confident those specs are correct for that
exact model rather than guessing. The price is taken directly from the CSV,
with no computed price range.

If the offer directory contains a more_data.txt file (see group_and_match.py),
its free-form content is passed to the model as an "additional_notes" field -
additional, truthful notes about that specific item, to be reformatted/edited
into the description rather than taken as an invitation to invent further
details.

The user prompt wraps all of the above into a single JSON payload (see
build_offer_json / build_user_prompt).

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
    "Dane o KONKRETNYM egzemplarzu (pola 'condition', 'damage', 'quantity') pochodzą "
    "WYŁĄCZNIE z podanego JSON-a - nie zmyślaj ani nie upiększaj tych informacji. Pole "
    "'condition' przepisz dokładnie tak, jak zostało podane - nie dodawaj własnej oceny "
    "jakości (np. 'w bardzo dobrym stanie', 'świetny stan'), jeśli takiej oceny nie ma w "
    "danych wejściowych. Jeśli pole 'damage' zawiera coś innego niż 'brak', wymień to "
    "wprost. Jeśli 'quantity' > 1, zaznacz to w opisie.\n\n"
    "Dodatkowo możesz uzupełnić opis o OGÓLNE, oficjalne dane techniczne produktu (np. moc, "
    "napięcie, rodzaj złącza, pojemność, wymiary, kompatybilność), jeśli na podstawie 'brand' "
    "i 'model' jesteś w stanie z dużą pewnością wskazać te dane - czyli są one publicznie "
    "znane dla tego dokładnego modelu, a nie zgadywane. Jeśli nie masz pewności co do "
    "konkretnej specyfikacji, po prostu ją pomiń - nie pisz zdań w stylu 'wymiary nie zostały "
    "podane' ani innych wzmianek o brakujących danych. Te dane dotyczą produktu jako takiego, "
    "a nie stanu czy historii tego konkretnego egzemplarza - nie myl ich z polem 'condition'.\n\n"
    "Czasem JSON zawiera też pole 'additional_notes' z surową notatką o tym produkcie - może "
    "to być np. wynik testu, historia użytkowania, zawartość zestawu, specyfikacja techniczna, "
    "ale może to też być skopiowany fragment strony sklepu/producenta. Wykorzystaj z niej "
    "tylko fakty o samym produkcie, które są przydatne kupującemu (specyfikacja, zawartość "
    "zestawu, kompatybilność, wyniki testów) i przeredaguj je do stylu reszty opisu. Pomiń w "
    "niej wszystko, co nie jest przydatne dla kupującego lub nie dotyczy tego konkretnego, "
    "sprzedawanego egzemplarza: reklamy innych produktów, politykę zwrotów/gwarancji sklepu, "
    "dane kontaktowe, adres producenta, numery katalogowe/EAN oraz ogólne instrukcje "
    "bezpieczeństwa i obsługi (np. ostrzeżenia z instrukcji użytkownika). Jeśli notatka "
    "zawiera stwierdzenie o stanie produktu (np. 'stan: nowy'), które jest sprzeczne z polem "
    "'condition' - zignoruj je; pole 'condition' zawsze ma pierwszeństwo, bo dotyczy "
    "faktycznie sprzedawanego egzemplarza, a nie ogólnej oferty ze strony źródłowej.\n\n"
    "Styl: konkretny, rzeczowy, bez marketingowego zachwytu - unikaj sformułowań typu 'idealny "
    "do', 'świetny wybór', 'gwarantuje najwyższą jakość', a także wezwań do zakupu typu "
    "'zapraszam do zakupu', 'zachęcam do zakupu', 'kup teraz', również przy opisywaniu "
    "specyfikacji technicznych. 3-6 zdań.\n\n"
    "Nie podawaj w treści opisu pól 'price' ani 'inpost_size' (cena i gabaryt paczki) - te "
    "informacje są dodawane automatycznie osobno, pod opisem."
)


def build_offer_json(data: dict, extra_notes: str = "") -> str:
    offer = {
        "name": data.get("name", ""),
        "brand": data.get("brand", ""),
        "model": data.get("model", ""),
        "condition": data.get("condition", ""),
        "damage": data.get("damage", ""),
        "quantity": data.get("quantity", ""),
        "price": data.get("price", ""),
        "inpost_size": data.get("inpost_size", ""),
    }
    if extra_notes:
        offer["additional_notes"] = extra_notes
    return json.dumps(offer, ensure_ascii=False, indent=2)


def build_user_prompt(data: dict, extra_notes: str = "") -> str:
    json_offer = build_offer_json(data, extra_notes)
    return (
        "Na podstawie danych poniżej wygeneruj opis oferty dla Allegro Lokalnie. "
        "Dodaj trochę ikonek dla czytelności opisu.\n"
        "Offer data (JSON):\n"
        "<<<JSON>>>\n"
        f"{json_offer}\n"
        "<<<END JSON>>>"
    )


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
