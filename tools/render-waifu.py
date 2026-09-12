#!/usr/bin/env python3
"""Render a .waifu pixel-art source file to a PNG. Standard library only.

Format of a .waifu file (lines are order-independent; '#' starts a comment):

    # optional comments
    name: Hikari            # display name (required)
    accent: F2C14E          # optional panel accent colour, RRGGBB or RRGGBBAA
    palette:
      .                     # '.' is transparent by convention (listed for clarity)
      1 2A1A3A              # token '1' -> colour #2A1A3A
      2 E8B8A0              # one colour per line, token is a single character
    grid:
      ....11111111....      # one row per line; any char not in the palette
      ....12222221....      # is transparent; ' ' and '.' are transparent

Leading indentation on any line is ignored, so you may indent the palette and
grid for readability. Grid rows are then left-aligned; use '.' (not spaces) for
deliberate transparent pixels.

Usage: render-waifu.py in.waifu out.png
"""
import struct
import sys
import zlib


def _chunk(tag: bytes, data: bytes) -> bytes:
    body = tag + data
    return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)


def _to_rgba(hexcol: str) -> bytes:
    hexcol = hexcol.strip().lstrip("#")
    if len(hexcol) == 6:
        r, g, b = int(hexcol[0:2], 16), int(hexcol[2:4], 16), int(hexcol[4:6], 16)
        return bytes((r, g, b, 255))
    if len(hexcol) == 8:
        r, g, b, a = int(hexcol[0:2], 16), int(hexcol[2:4], 16), int(hexcol[4:6], 16), int(hexcol[6:8], 16)
        return bytes((r, g, b, a))
    raise ValueError("bad colour: " + hexcol)


def parse(path: str):
    name, accent = "waifu", None
    palette, grid, section = {}, [], None
    with open(path, encoding="utf-8") as fh:
        for raw in fh:
            # Strip leading indentation as well as the newline: palette and grid
            # lines are often indented for readability, and leading spaces are
            # never meaningful art (transparent pixels are '.', not whitespace).
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            low = line.lower()
            if low.startswith("name:"):
                name = line.split(":", 1)[1].strip()
            elif low.startswith("accent:"):
                accent = line.split(":", 1)[1].strip().lstrip("#")
            elif low.startswith("palette:"):
                section = "palette"
            elif low.startswith("grid:"):
                section = "grid"
            elif section == "palette":
                token, _, hexcol = line.partition(" ")
                token, hexcol = token.strip(), hexcol.strip()
                if token and hexcol:
                    palette[token] = hexcol
            elif section == "grid":
                grid.append(line)
    return name, accent, palette, grid


def render(path: str, out: str) -> None:
    name, accent, palette, grid = parse(path)
    h = len(grid)
    w = max((len(r) for r in grid), default=0)
    if w == 0 or h == 0:
        raise SystemExit("empty grid in " + path)
    raw = bytearray()
    for row in grid:
        raw.append(0)  # filter byte: none
        for x in range(w):
            ch = row[x] if x < len(row) else "."
            raw += _to_rgba(palette[ch]) if ch in palette else b"\x00\x00\x00\x00"
    ihdr = struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)
    png = (b"\x89PNG\r\n\x1a\n" + _chunk(b"IHDR", ihdr)
           + _chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + _chunk(b"IEND", b""))
    with open(out, "wb") as fh:
        fh.write(png)
    tag = f" (accent #{accent})" if accent else ""
    print(f"{name}: {w}x{h} -> {out}{tag}")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        print(__doc__)
        sys.exit(1)
    render(sys.argv[1], sys.argv[2])
