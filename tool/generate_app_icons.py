#!/usr/bin/env python
"""Export the approved launcher artwork using ImageMagick (no design changes)."""

import json
from pathlib import Path
import shutil
import struct
import subprocess


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "assets/branding/app-icon.png"
IOS = ROOT / "ios/Runner/Assets.xcassets/AppIcon.appiconset"
ANDROID = ROOT / "app/src/main/res/drawable-nodpi/ic_launcher_bear.png"


def png_info(path: Path) -> tuple[int, int, int]:
    with path.open("rb") as stream:
        header = stream.read(29)
    if header[:8] != b"\x89PNG\r\n\x1a\n" or header[12:16] != b"IHDR":
        raise ValueError(f"Not a PNG: {path}")
    width, height, _, color_type = struct.unpack(">IIBB", header[16:26])
    return width, height, color_type


def export(magick: str, destination: Path, pixels: int) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        [magick, str(SOURCE), "-resize", f"{pixels}x{pixels}",
         "-alpha", "off", "-depth", "8", "-strip", f"PNG24:{destination}"],
        check=True,
    )
    # RGB PNGs without alpha are suitable for the existing iOS AppIcon catalog.
    if png_info(destination) != (pixels, pixels, 2):
        raise ValueError(f"Incorrect dimensions or PNG color type: {destination}")


def main() -> None:
    magick = shutil.which("magick")
    if magick is None:
        raise SystemExit("ImageMagick is required; install it and put magick on PATH.")
    width, height, _ = png_info(SOURCE)
    if width != height or width < 1024:
        raise SystemExit("The source must be a square PNG at least 1024 pixels wide.")

    entries = json.loads((IOS / "Contents.json").read_text())["images"]
    sizes: dict[str, int] = {}
    for entry in entries:
        size, other = entry["size"].split("x")
        scale = float(entry["scale"].removesuffix("x"))
        pixels = float(size) * scale
        if size != other or not pixels.is_integer():
            raise ValueError(f"Unsupported icon size: {entry}")
        filename = entry["filename"]
        if filename in sizes and sizes[filename] != int(pixels):
            raise ValueError(f"Conflicting sizes for {filename}")
        sizes[filename] = int(pixels)

    for filename, pixels in sizes.items():
        export(magick, IOS / filename, pixels)
    # A nodpi bitmap avoids density-dependent scaling; the XML sets its bounds.
    export(magick, ANDROID, 432)
    print(f"Exported {len(sizes)} iOS icons and the Android adaptive artwork.")


if __name__ == "__main__":
    main()
