#!/usr/bin/env python3
"""
Extracts the Pokémon Storage System box wallpapers from a Gen 3 Emerald ROM.

    python3 tools/extract-storage-graphics.py "path/to/emerald.gba" [art/pc]

The ROM is never committed and neither is its output — `art/` is gitignored, and
SHARED_WORKSPACE.md forbids game files in the repository or in a release jar.
This runs on the machine that owns the ROM and writes PNGs beside the jar, the
same route the Pokémon sprites already take.

Finding the table rather than hardcoding an offset
--------------------------------------------------
`gWallpaperTable` is an array of `{ const u32 *tiles; const u32 *tilemap;
const u16 *palette; }`. Both graphics pointers address LZ77 blocks, which start
with a 0x10 byte, so a run of 12-byte records whose first two words point at
LZ77 data inside the ROM is a strong and self-describing signature. A hack that
moved the table still gets found; a hack that changed its shape fails loudly
here instead of writing sixteen files of noise.

Every assumption is then checked against the format rather than assumed:
sixteen entries, a 720-byte tilemap (20x18 tiles) per entry, and a first
wallpaper that decodes to exactly 160x144. If any of that is wrong the script
stops and says which entry disagreed.
"""

import struct, sys, pathlib, zlib

BASE = 0x08000000
TILES_WIDE, TILES_HIGH = 20, 18
WALLPAPERS = 16


def lz77(rom, off):
    """GBA BIOS LZ77 (type 0x10)."""
    if rom[off] != 0x10:
        raise ValueError(f"0x{off:X} is not an LZ77 block")
    size = struct.unpack_from("<I", rom, off)[0] >> 8
    out = bytearray()
    p = off + 4
    while len(out) < size:
        flags = rom[p]; p += 1
        for bit in range(8):
            if len(out) >= size:
                break
            if flags & (0x80 >> bit):
                b0, b1 = rom[p], rom[p + 1]; p += 2
                length = (b0 >> 4) + 3
                disp = ((b0 & 0xF) << 8 | b1) + 1
                start = len(out) - disp
                if start < 0:
                    raise ValueError("back-reference before the start of the stream")
                for k in range(length):
                    out.append(out[start + k])
            else:
                out.append(rom[p]); p += 1
    return bytes(out[:size])


def find_wallpaper_table(rom):
    """The 12-byte-record run whose tilemaps are all one screen of tiles."""
    size = len(rom)
    words = struct.unpack_from(f"<{size // 4}I", rom, 0)

    def target(w):
        return w - BASE if BASE <= w < BASE + size else -1

    def is_lz(i):
        t = target(words[i])
        return 0 <= t < size - 4 and rom[t] == 0x10

    candidates = []
    i = 0
    while i < len(words) - 2:
        if is_lz(i) and is_lz(i + 1) and target(words[i + 2]) >= 0:
            start, count, j = i, 0, i
            while j < len(words) - 2 and is_lz(j) and is_lz(j + 1) and target(words[j + 2]) >= 0:
                count += 1
                j += 3
            if count >= WALLPAPERS:
                candidates.append((start * 4, count))
            i = j
        else:
            i += 1

    # The wallpapers are the run whose every tilemap is exactly one 20x18 screen.
    for off, count in candidates:
        try:
            maps = [len(lz77(rom, struct.unpack_from("<I", rom, off + e * 12 + 4)[0] - BASE))
                    for e in range(WALLPAPERS)]
        except Exception:
            continue
        if all(m == TILES_WIDE * TILES_HIGH * 2 for m in maps):
            return off
    raise SystemExit("No wallpaper table found: no run of 16 records had 20x18 tilemaps. "
                     f"Runs examined: {[(hex(o), c) for o, c in candidates]}")


def render(rom, entry):
    tiles_at, map_at, pal_at = [struct.unpack_from("<I", rom, entry + k * 4)[0] - BASE for k in range(3)]
    tiles, tmap = lz77(rom, tiles_at), lz77(rom, map_at)
    if len(tmap) != TILES_WIDE * TILES_HIGH * 2:
        raise SystemExit(f"tilemap at 0x{map_at:X} is {len(tmap)} bytes, expected "
                         f"{TILES_WIDE * TILES_HIGH * 2}")
    palette = []
    for i in range(32):
        v = struct.unpack_from("<H", rom, pal_at + i * 2)[0]
        r, g, b = v & 31, (v >> 5) & 31, (v >> 10) & 31
        palette.append((r * 255 // 31, g * 255 // 31, b * 255 // 31, 255))

    width, height = TILES_WIDE * 8, TILES_HIGH * 8
    pixels = [[-1] * width for _ in range(height)]
    for i in range(TILES_WIDE * TILES_HIGH):
        cell = struct.unpack_from("<H", tmap, i * 2)[0]
        bank = cell >> 12
        # Game loads two palettes at banks 4/5 and adds 3 to map bank IDs.
        # Bank 0 is the surrounding UI and stays transparent in this asset.
        if bank == 0:
            continue
        if bank > 2:
            raise ValueError("Unsupported wallpaper palette bank")
        index, hflip, vflip = cell & 0x3FF, cell & 0x400, cell & 0x800
        tx, ty = (i % TILES_WIDE) * 8, (i // TILES_WIDE) * 8
        for y in range(8):
            sy = 7 - y if vflip else y
            for x in range(8):
                sx = 7 - x if hflip else x
                at = index * 32 + sy * 4 + sx // 2
                if at >= len(tiles):
                    raise ValueError("Invalid wallpaper tile")
                byte = tiles[at]
                pixels[ty + y][tx + x] = ((byte >> 4) if (sx & 1) else (byte & 0xF)) + (bank - 1) * 16
    return width, height, pixels, palette


def write_png(path, width, height, pixels, palette):
    raw = bytearray()
    for y in range(height):
        raw.append(0)
        for x in range(width):
            raw += bytes(palette[pixels[y][x]]) if pixels[y][x] >= 0 else bytes(4)

    def chunk(tag, body):
        return (struct.pack(">I", len(body)) + tag + body
                + struct.pack(">I", zlib.crc32(tag + body) & 0xFFFFFFFF))

    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n")
        f.write(chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)))
        f.write(chunk(b"IDAT", zlib.compress(bytes(raw), 9)))
        f.write(chunk(b"IEND", b""))


def main():
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    rom = pathlib.Path(sys.argv[1]).read_bytes()
    out = pathlib.Path(sys.argv[2] if len(sys.argv) > 2 else "art/pc")

    code = rom[0xAC:0xB0].decode("ascii", errors="replace")
    if code != "BPEE":
        print(f"warning: game code is {code!r}, expected BPEE (Emerald). Continuing.", file=sys.stderr)

    table = find_wallpaper_table(rom)
    print(f"gWallpaperTable at 0x{table:06X} (pointer 0x{table + BASE:08X})")

    out.mkdir(parents=True, exist_ok=True)
    for e in range(WALLPAPERS):
        width, height, pixels, palette = render(rom, table + e * 12)
        if (width, height) != (160, 144):
            raise SystemExit(f"wallpaper {e} decoded to {width}x{height}, expected 160x144")
        path = out / f"wallpaper-{e:02d}.png"
        write_png(path, width, height, pixels, palette)
        print(f"  {path}  {width}x{height}")
    print(f"{WALLPAPERS} wallpapers written. They are game files: do not commit them.")


if __name__ == "__main__":
    main()
