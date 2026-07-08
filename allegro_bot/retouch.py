"""Retusz zdjęć ofert: automatyczny balans bieli (gray-world) + auto-kontrast/jasność.

Świadomie NIE robimy automatycznego kadrowania/wycinania tła - ryzyko błędnego
wykrycia krawędzi przy różnych kształtach przedmiotów jest zbyt duże, więc
kadrowanie zostaje manualne.
"""
from pathlib import Path

from PIL import Image, ImageOps, ImageStat

from allegro_bot import config

JPEG_QUALITY = 90


def gray_world_white_balance(img: Image.Image) -> Image.Image:
    stat = ImageStat.Stat(img)
    r_mean, g_mean, b_mean = stat.mean
    gray_mean = (r_mean + g_mean + b_mean) / 3
    scales = [gray_mean / m if m > 0 else 1.0 for m in (r_mean, g_mean, b_mean)]
    bands = img.split()
    scaled_bands = [
        band.point(lambda x, s=scale: min(255, max(0, round(x * s))))
        for band, scale in zip(bands, scales)
    ]
    return Image.merge("RGB", scaled_bands)


def retouch_image(src: Path, dest: Path) -> None:
    with Image.open(src) as img:
        img = ImageOps.exif_transpose(img.convert("RGB"))
        img = gray_world_white_balance(img)
        img = ImageOps.autocontrast(img, cutoff=1)
        img.save(dest, "JPEG", quality=JPEG_QUALITY)


def retouch_offer(offer_dir: Path) -> None:
    photos_dir = offer_dir / "zdjecia"
    retusz_dir = offer_dir / "retusz"

    photos = sorted(p for p in photos_dir.iterdir() if p.suffix.lower() in (".jpg", ".jpeg"))
    if not photos:
        return

    if retusz_dir.exists() and len(list(retusz_dir.iterdir())) == len(photos):
        print(f"{offer_dir.name}: retusz już wykonany, pomijam.")
        return

    retusz_dir.mkdir(exist_ok=True)
    for photo in photos:
        retouch_image(photo, retusz_dir / photo.name)

    print(f"{offer_dir.name}: zretuszowano {len(photos)} zdjęć.")


def retouch_all() -> None:
    if not config.OFFERS_DIR.exists():
        print(f"Katalog {config.OFFERS_DIR} nie istnieje, brak ofert do retuszu.")
        return

    for offer_dir in sorted(config.OFFERS_DIR.iterdir()):
        if offer_dir.is_dir():
            retouch_offer(offer_dir)


if __name__ == "__main__":
    retouch_all()
