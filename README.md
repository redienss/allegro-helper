# AllegroBot

Narzędzie wspomagające wyprzedaż prywatnych przedmiotów na **Allegro Lokalnie**. Celem projektu jest częściowa automatyzacja workflow od etapu robienia zdjęć aż po tworzenie opisów ofert — na tyle, na ile pozwala na to regulamin Allegro (samo wystawianie ofert pozostaje manualne).

Projekt jest w języku polskim, ponieważ Allegro Lokalnie działa w języku polskim (opisy ofert, nazwy, komunikacja z kupującymi).

## Obecny workflow (manualny)

1. Zdjęcia przedmiotu wykonywane są na obrotowym turntable, sterowanym pilotem, telefonem na statywie z aplikacją **OpenCamera** — seria 20 zdjęć, jedno co 5 sekund.
2. Zdjęcia zgrywane są z telefonu na komputer.
   Lokalizacja na telefonie: `mtp://SAMSUNG_SAMSUNG_Android_R58R301MAHN/Pamięć wewnętrzna/DCIM/OpenCamera`
3. Zdjęcia są poprawiane (balans bieli, jasność, kontrast) tak, żeby zachować dobrą jasność bez wypalania detali.
4. Spisywane są podstawowe informacje o przedmiocie: model, ilość sztuk, stan, dodatkowe informacje, wielkość przesyłki (gabaryt InPost), cena.
5. Tworzony/generowany jest opis oferty.
6. Oferta jest ręcznie tworzona na Allegro Lokalnie z użyciem zdjęć i opisu.

## Etapy do automatyzacji

1. **Wczytanie CSV** ze zbiorczą listą ofert do przygotowania (plik `oferty.csv`, kolumny: `nazwa | marka | model | stan | uszkodzenia | ilość | cena | gabaryt InPost`).
2. **Import zdjęć** z telefonu (lokalizacja MTP jak wyżej) do katalogu roboczego projektu.
3. **Dopasowanie zdjęć do wierszy CSV** — np. przy 10 wierszach w `oferty.csv` powinno być 10 × 20 zdjęć w tej samej kolejności co wiersze w pliku.
4. **Podział na katalogi ofert** — każda seria zdjęć trafia do osobnego katalogu nazwanego wg daty i czasu pierwszego zdjęcia w serii, np. `20260708_0316`.
5. **Retusz zdjęć** — automatyczna poprawa balansu bieli i kontrastu/jasności (gray-world + auto-kontrast). Kadrowanie pozostaje na razie manualne — ryzyko błędnego automatycznego wycięcia przedmiotu przy różnych kształtach jest zbyt duże.
6. **Generowanie opisu oferty** — plik tekstowy per oferta, np. `20260708_0316/opis.txt`.
7. **Wycena** — sugerowane widełki cenowe zapisywane razem z opisem.

## Etapy pozostające manualne

1. **Zdjęcia** — turntable i statyw działają pół-automatycznie (pilot), ale seria wymaga obecności i obsługi.
2. **Przygotowanie pliku CSV** — musi być uzupełnione ręcznie (np. model przedmiotu), żeby dało się wygenerować sensowny opis.
3. **Wystawienie oferty na Allegro Lokalnie** — na razie ręcznie, na podstawie wygenerowanych zdjęć i opisu.

## Struktura danych wejściowych

### `oferty.csv`

| kolumna | opis | przykład |
|---|---|---|
| nazwa | pełna nazwa oferty | Ładowarka ścienna Poss PSWCC-2.4WH-18 typu C – biała |
| marka | marka produktu | Poss |
| model | model produktu | PSWCC-2.4WH-18 |
| stan | stan przedmiotu | używany |
| uszkodzenia | opis uszkodzeń/wad | brak |
| ilość | liczba sztuk | 1 |
| cena | cena w PLN | 10 |
| gabaryt InPost | rozmiar paczkomatowy | A |

Wiersze w pliku muszą być w tej samej kolejności, w jakiej wykonywane były serie zdjęć.

### Zdjęcia źródłowe

Telefon (Samsung, MTP): `mtp://SAMSUNG_SAMSUNG_Android_R58R301MAHN/Pamięć wewnętrzna/DCIM/OpenCamera`

Nazwa pliku wg konwencji OpenCamera: `IMG_YYYYMMDD_HHMMSS.jpg`. Zdjęcia w obrębie jednej serii (jednej oferty) są rozdzielone ~5 sekundami; między seriami występuje wyraźnie dłuższa przerwa (czas potrzebny na podmianę przedmiotu na turntable) — to pozwala automatycznie wykryć granice serii.

## Struktura danych wyjściowych

Dla każdej oferty powstaje katalog `oferty/<YYYYMMDD_HHMM>/` (znacznik czasu pierwszego zdjęcia serii):

```
oferty/20260708_0340/
  dane.json      # dane z wiersza CSV + lista zdjęć
  zdjecia/       # oryginalne zdjęcia serii
  retusz/        # zdjęcia po auto-balansie bieli i auto-kontraście
  opis.txt       # wygenerowany opis + cena/widełki cenowe
```

## Wymagania techniczne

- Python 3 (środowisko: `python3 -m venv .venv && .venv/bin/pip install -r requirements.txt`).
- Pillow — przetwarzanie zdjęć.
- `gio` (GVFS) do odczytu plików z telefonu podłączonego po MTP (montowane w `/run/user/<uid>/gvfs/mtp:host=...`).
- Klucz OpenAI API (zmienna `OPENAI_API_KEY` w pliku `.env`, patrz `.env.example`) — potrzebny tylko do generowania opisów.

## Instalacja i użycie

```bash
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
cp .env.example .env   # i uzupełnij OPENAI_API_KEY

.venv/bin/python main.py import    # kopiuje zdjęcia z telefonu do raw_photos/ (oryginały zostają na telefonie)
.venv/bin/python main.py match     # dopasowuje zdjęcia do wierszy oferty.csv, tworzy katalogi w oferty/
.venv/bin/python main.py retouch   # auto-balans bieli + auto-kontrast dla każdej oferty
.venv/bin/python main.py describe  # generuje opis.txt (wymaga OPENAI_API_KEY)

# albo wszystko na raz:
.venv/bin/python main.py all
```

Każdy krok jest bezpieczny do wielokrotnego uruchomienia — już przetworzone oferty/zdjęcia są pomijane.
