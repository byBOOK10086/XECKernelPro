"""Generate an original tiny TTF fixture; no third-party font is redistributed.

Only needed to regenerate the committed fixture: python -m pip install fonttools
"""
from pathlib import Path
from fontTools.fontBuilder import FontBuilder
from fontTools.pens.ttGlyphPen import TTGlyphPen

builder = FontBuilder(1000, isTTF=True)
builder.setupGlyphOrder(['.notdef', 'space', 'A'])
builder.setupCharacterMap({32: 'space', 65: 'A'})
glyphs = {}
for name in ['.notdef', 'space', 'A']:
    pen = TTGlyphPen(None)
    if name != 'space':
        pen.moveTo((50, 0))
        pen.lineTo((250, 700))
        pen.lineTo((450, 0))
        pen.closePath()
    glyphs[name] = pen.glyph()
builder.setupGlyf(glyphs)
builder.setupHorizontalMetrics({name: (500, 0) for name in glyphs})
builder.setupHorizontalHeader(ascent=800, descent=-200)
builder.setupNameTable({'familyName': 'XEC Test', 'styleName': 'Regular',
                       'uniqueFontIdentifier': 'XEC-Test-Regular', 'fullName': 'XEC Test Regular',
                       'psName': 'XECTest-Regular'})
builder.setupOS2(sTypoAscender=800, sTypoDescender=-200, usWinAscent=800, usWinDescent=200)
builder.setupPost()
builder.setupMaxp()
target = Path(__file__).resolve().parents[1] / 'manager/app/src/test/resources/theme-test.ttf'
target.parent.mkdir(parents=True, exist_ok=True)
builder.save(target)
print('Generated', target.name)
