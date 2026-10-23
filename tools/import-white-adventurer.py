"""Import the new white badge separately; --legacy-silver rebuilds the old badge."""
from pathlib import Path
import argparse
import shutil
from import_item_models import ROOT, ASSETS, dump, export_item

LEGACY_SOURCE = ROOT / 'artwork/imports/white_adventurer/白色冒险者.bbmodel'
NEW_SOURCE = ROOT / 'artwork/imports/white_adventurer_badge/白色冒险者 (1).bbmodel'


def import_badge(source, legacy_silver=False):
    retained = LEGACY_SOURCE if legacy_silver else NEW_SOURCE
    source = Path(source)
    retained.parent.mkdir(parents=True, exist_ok=True)
    if source.resolve() != retained.resolve():
        shutil.copyfile(source, retained)
    ident = 'silver_adventurer_badge' if legacy_silver else 'white_adventurer_badge'
    record = export_item(retained, ident, '银色冒险者牌' if legacy_silver else '白色冒险者牌', 'flat')
    record['item'] = record['id']
    record['replacementTargetConfirmedByUser'] = legacy_silver
    record['scope'] = ('Retain the 0.7.3 silver item replacement and its original source.' if legacy_silver else
                       'Separate white adventurer badge; preserve all 544 source cuboids, five visible root groups and original embedded PNG. Do not replace silver.')
    dump(retained.parent / 'manifest.json', record)
    if legacy_silver:
        dump(ASSETS / f'art/item_replacements/{ident}.json', record)
    return record


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('source', nargs='?', type=Path)
    parser.add_argument('--legacy-silver', action='store_true', help='Explicitly rebuild the confirmed 169-cube silver replacement')
    args = parser.parse_args()
    record = import_badge(args.source or (LEGACY_SOURCE if args.legacy_silver else NEW_SOURCE), args.legacy_silver)
    print(f'Imported {record["id"]}: {record["elements"]} cuboids, {record["faces"]} textured faces')


if __name__ == '__main__':
    main()
