"""Rebuild all imported FSRPG items; never write registration or translations."""
import importlib.util
from import_item_models import ROOT, dump, export_item

SOURCE = ROOT / 'artwork/imports/261001'
ENTRIES = [
    ('money_pouch', '钱袋', 'qiandai.bbmodel', 'pouch', None),
    ('gold_coin', '金币', '硬币.bbmodel', 'upright_coin', None),
    ('silver_coin', '银币', '硬币.bbmodel', 'upright_coin', 0),
    ('crystal_coin', '晶币', '金币.bbmodel', 'flat', None),
    ('nether_coin', '下界币', '下届币.bbmodel', 'flat', None),
    ('gold_adventurer_badge', '金色冒险者牌', '金色冒险者牌.bbmodel', 'flat', None),
    ('cobalt_adventurer_badge', '钴色冒险者牌', '钴色冒险者牌.bbmodel', 'flat', None),
    ('copper_adventurer_badge', '铜色冒险者牌', '铜色冒险者牌.bbmodel', 'flat', None),
]


def main():
    records = [export_item(SOURCE / file, ident, label, profile, override)
               for ident, label, file, profile, override in ENTRIES]
    # The old 169-cube appearance was assigned to silver in 0.7.3. The newly
    # supplied 544-cube white badge is separate and cannot overwrite silver.
    spec = importlib.util.spec_from_file_location('white_adventurer_import', ROOT / 'tools/import-white-adventurer.py')
    white = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(white)
    records.append(white.import_badge(white.LEGACY_SOURCE, legacy_silver=True))
    records.append(white.import_badge(white.NEW_SOURCE))
    dump(SOURCE / 'import-manifest.json', {
        'minecraft': '1.21.11', 'nativeEulerXYZ': True,
        'items': records[:9], 'layoutSource': '综合.bbmodel',
        'notes': 'Original sources retained; silver coin uses the original silver texture variant. Silver badge retains the confirmed 0.7.3 replacement. New white badge has a separate ID. Every display context is fitted from rotated bounds.',
    })
    dump(ROOT / 'artwork/imports/item-models-manifest.json', {
        'minecraft': '1.21.11', 'nativeEulerXYZ': True, 'items': records,
        'rebuild': 'python tools/import-261001.py',
        'scope': '10 static FSRPG items; no forging, creature, HUD or animation assets',
    })
    print(f'Imported {len(records)} native item models with nine fitted display contexts each')


if __name__ == '__main__':
    main()
