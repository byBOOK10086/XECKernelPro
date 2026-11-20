"""Keep the theme editor catalog and resource-shrinker roots in sync with app strings."""
import json
from pathlib import Path
import xml.etree.ElementTree as ET

main = Path(__file__).resolve().parents[1] / 'manager/app/src/main'
keys = sorted({node.attrib['name'] for file in (main / 'res/values').glob('*.xml')
               for node in ET.parse(file).getroot().findall('string')
               if node.get('translatable') != 'false'
               and not node.attrib['name'].endswith(('_url', '_uri', '_path'))
               and not ''.join(node.itertext()).strip().startswith(('https://', 'http://'))})
(main / 'assets/theme-text-keys.json').write_text(json.dumps(keys, indent=2) + '\n', encoding='utf-8', newline='\n')
(main / 'res/raw/theme_text_keep.xml').write_text(
    '<resources xmlns:tools="http://schemas.android.com/tools" tools:keep="' +
    ','.join('@string/' + key for key in keys) + '" />\n', encoding='utf-8', newline='\n')
print('Theme text catalog:', len(keys), 'strings')
