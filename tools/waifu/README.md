# Waifu portraits

The eight decorative portraits shown on the Today page are original pixel art
made for Yoru. They are not game assets and not anyone's personal files — they
are the app's own art, and they ship inside the jar.

Each portrait lives in two forms:

- **`*.waifu`** — the source: a 48×48 character grid with a small palette.
  This is what you edit.
- **`src/main/resources/dev/yoru/waifu/*.png`** — the rendered 48×48 PNG the
  app loads at run time from the classpath.

To change a portrait, edit its `.waifu` file here and re-render it:

```sh
python3 tools/render-waifu.py tools/waifu/hikari.waifu \
    src/main/resources/dev/yoru/waifu/hikari.png
```

The renderer is standard-library-only Python and is documented in its own
header (`tools/render-waifu.py`). The grid format is simple:

- `name:` — the display name shown in Settings.
- `accent:` — an optional RRGGBB accent colour (currently unused by the panel).
- `palette:` — one `token colour` line per colour; a single `.` is transparent.
- `grid:` — 48 rows of 48 characters; any character not in the palette is
  transparent, and leading indentation is ignored.

The roster — the id, the display name, and the order in the Settings picker —
is defined in `WaifuCatalog`, which must stay in sync with the files here.
