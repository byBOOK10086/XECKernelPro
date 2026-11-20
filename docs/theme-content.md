# Theme fonts and interface text

In Theme Store → Editor, choose a TTF or OTF font (up to 12 MB), or search the
interface text list and replace individual labels. Reset restores the built-in
value. Save the theme, then apply it or upload it from My Themes.

Fonts and text travel in the same `.xectheme` file as colors and backgrounds.
Export creates a JPEG preview even when a theme contains only fonts and text,
so it can pass through the existing store ingestion path. Uploads still use the
configured theme server, not GitHub. Authors should use fonts they can share.

Optional schema 2 fields:

```json
{
  "schema": 2,
  "id": "my-theme",
  "name": "My theme",
  "versionCode": 1,
  "globalFont": "global-font.ttf",
  "textOverrides": {
    "home_brand_title": "My phone",
    "confirm": "Go"
  }
}
```

`globalFont` names a file in the package root. Import checks the font header,
table bounds, size and Android font decoding before replacing an installed theme.
Missing or invalid fonts reject the installation. Old clients ignore these new
optional fields; old themes retain the default font and wording.

Text uses stable Android string-resource names, not text matching. Replacements
apply to manager resource strings, including home labels and buttons. Actual
device values, Android system dialogs, module WebUIs and custom canvas lettering
are not rewritten. Link targets and internal configuration resources are excluded
from both the editor and runtime replacement. Replacement wording is used across app languages. Format
placeholders must keep their types and argument indexes; an incompatible locale
falls back to its built-in string. Unknown resource names are preserved for newer
clients but have no effect on this client.

Switching themes replaces the entire font/text selection. Saving an active theme
with the same ID invalidates its cached content. Resetting the theme restores the
default font and text. The editor always displays the original resource text for
comparison, even while an altered theme is active.

After adding app strings, run `python scripts/generate_theme_text_catalog.py`.
The generated catalog and resource keep list are checked in CI.
