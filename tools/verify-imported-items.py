"""Audit generated static item geometry, UVs, PNGs and fitted render bounds.

The optional CPU preview is a texture/geometry review aid, not game validation.
The release workflow must still render the final JAR in the real client.
"""
from pathlib import Path
import argparse
import base64
import hashlib
import itertools
import json
import math
import numpy as np

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'src/main/resources/assets/fsrpg'
CONTEXTS = {'gui', 'ground', 'fixed', 'head', 'firstperson_righthand',
            'firstperson_lefthand', 'thirdperson_righthand', 'thirdperson_lefthand', 'on_shelf'}
FACE_CORNERS = {
    'down': [(0,0,1),(0,0,0),(1,0,0),(1,0,1)], 'up': [(0,1,0),(0,1,1),(1,1,1),(1,1,0)],
    'north': [(1,1,0),(1,0,0),(0,0,0),(0,1,0)], 'south': [(0,1,1),(0,0,1),(1,0,1),(1,1,1)],
    'west': [(0,1,0),(0,0,0),(0,0,1),(0,1,1)], 'east': [(1,1,1),(1,0,1),(1,0,0),(1,1,0)],
}


def load(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def axis_matrix(axis, angle):
    a = np.zeros(3)
    a[axis] = 1
    skew = np.array([[0, -a[2], a[1]], [a[2], 0, -a[0]], [-a[1], a[0], 0]])
    angle = math.radians(angle)
    return np.eye(3) + math.sin(angle) * skew + (1 - math.cos(angle)) * (skew @ skew)


def matrix(angles, display=False):
    result = np.eye(3)
    for axis in (range(2, -1, -1) if display else range(3)):
        result = axis_matrix(axis, angles[axis]) @ result
    return result


def geometry(element, corners=None):
    r = element.get('rotation', {})
    rotation = matrix([r.get(a, 0) for a in 'xyz'])
    origin = np.array(r.get('origin', [8, 8, 8]))
    scale = 1 / np.abs(rotation).max(axis=0) if r.get('rescale') else np.ones(3)
    corners = corners if corners is not None else itertools.product((0, 1), repeat=3)
    points = np.array([np.where(corner, element['to'], element['from']) for corner in corners])
    return ((points - origin) * scale) @ rotation.T + origin


def displayed(points, transform, left=False):
    angles = list(transform['rotation'])
    offset = np.array(transform['translation'])
    if left:
        angles[1], angles[2] = -angles[1], -angles[2]
        offset[0] *= -1
    return ((points - 8) * transform['scale']) @ matrix(angles, display=True).T + offset


def expected_elements(source):
    by_id = {e['uuid']: e for e in source['elements']}
    groups = {g['uuid']: g for g in source.get('groups', [])}
    allowed = set()

    def walk(node, visible):
        uid = node if isinstance(node, str) else node['uuid']
        value = by_id.get(uid, groups.get(uid, node))
        visible = visible and value.get('visibility', True) and value.get('export', True)
        if uid in by_id:
            if visible:
                allowed.add(uid)
        else:
            assert not visible or not any(value.get('rotation', [0, 0, 0])), 'unbaked group rotation'
            for child in node.get('children', []):
                walk(child, visible)

    for node in source.get('outliner', []):
        walk(node, True)
    if not source.get('outliner'):
        allowed = {e['uuid'] for e in source['elements'] if e.get('visibility', True) and e.get('export', True)}
    return [e for e in source['elements'] if e['uuid'] in allowed and
            any(f.get('texture') is not None and f['texture'] != -1 for f in e['faces'].values())]


def verify(record):
    ident = record['id'].split(':', 1)[1]
    source_path = ROOT / record['source']
    assert hashlib.sha256(source_path.read_bytes()).hexdigest() == record['sourceSha256'], ident
    source = load(source_path)
    model = load(ASSETS / f'models/item/{ident}.json')
    item = load(ASSETS / f'items/{ident}.json')
    assert item == {'model': {'type': 'minecraft:model', 'model': f'fsrpg:item/{ident}'}}, ident
    originals = expected_elements(source)
    assert len(originals) == len(model['elements']) == record['elements'], ident
    faces_checked = 0
    for original, native in zip(originals, model['elements']):
        inflate = original.get('inflate', 0)
        np.testing.assert_array_equal(np.array(original['from']) - inflate, native['from'], err_msg=ident)
        np.testing.assert_array_equal(np.array(original['to']) + inflate, native['to'], err_msg=ident)
        r = native.get('rotation', {})
        np.testing.assert_array_equal(original.get('rotation', [0, 0, 0]), [r.get(a, 0) for a in 'xyz'])
        if r:
            assert r['origin'] == original.get('origin', [8, 8, 8])
            assert r['rescale'] == original.get('rescale', False)
        expected = {s: f for s, f in original['faces'].items() if f.get('texture') is not None and f['texture'] != -1}
        assert set(expected) == set(native['faces']), ident
        for side, face in expected.items():
            index = int(face['texture']) if record['textureOverride'] is None else record['textureOverride']
            texture = source['textures'][index]
            width, height = texture.get('uv_width', texture['width']), texture.get('uv_height', texture['height'])
            exported = native['faces'][side]
            np.testing.assert_allclose(np.array(exported['uv']) * [width, height, width, height] / 16, face['uv'], rtol=0, atol=1e-12)
            assert exported['texture'] == f'#{index}'
            assert exported.get('rotation', 0) == face.get('rotation', 0)
            assert native.get('shade', True) == original.get('shade', True)
            faces_checked += 1
    for index, texture in enumerate(source['textures']):
        original_png = base64.b64decode(texture['source'].split(',', 1)[1])
        exported_png = (ASSETS / f'textures/item/{ident}_{index}.png').read_bytes()
        assert exported_png == original_png, f'{ident}: PNG bytes changed'
        assert hashlib.sha256(exported_png).hexdigest() == record['textures'][index]['sha256']
    assert set(model['display']) == CONTEXTS, ident
    points = np.concatenate([geometry(e) for e in model['elements']])
    bounds = {}
    first_person_projection = {}
    for context, transform in model['display'].items():
        assert all(np.all(np.isfinite(transform[key])) for key in ('rotation', 'translation', 'scale'))
        assert all(0 < s <= 4 for s in transform['scale'])
        assert max(abs(t) for t in transform['translation']) <= 80
        p = displayed(points, transform, context.endswith('lefthand'))
        lo, hi = p.min(0), p.max(0)
        center = (lo + hi) / 2
        if context in ('gui', 'fixed', 'on_shelf'):
            assert np.max(np.abs(center)) < .00002, f'{ident} {context}: off center {center}'
            limit = {'gui': 6.75, 'fixed': 6.5, 'on_shelf': 4}[context]
            assert np.max(np.abs(p[:, :2])) <= limit + .00002, f'{ident} {context}: clipped'
        elif context == 'ground':
            assert abs(lo[1] - .5) < .00002 and max(hi - lo) <= 5.00002
        elif context.startswith('firstperson_'):
            left = context.endswith('lefthand')
            np.testing.assert_allclose(center, [2 if left else -2, 6, 0], atol=.00002)
            assert max((hi - lo)[:2]) <= 5.50002
            # Check the real vanilla idle hand pipeline, not only local model
            # bounds: ItemInHandRenderer's arm translation and GameRenderer's
            # fixed 70-degree hand FOV. Include depth-dependent magnification.
            camera = p / 16 + [-.56 if left else .56, -.52, -.72]
            assert camera[:, 2].max() < -.05
            projected = camera[:, :2] / -camera[:, 2:3] / math.tan(math.radians(35))
            first_person_projection[context] = {}
            for aspect_name, aspect in [('4:3', 4 / 3), ('1200:780', 1200 / 780), ('16:9', 16 / 9)]:
                ndc = projected / [aspect, 1]
                assert np.max(np.abs(ndc)) < .95, f'{ident} {context} {aspect_name}: viewport margin too small'
                first_person_projection[context][aspect_name] = [ndc.min(0).round(6).tolist(), ndc.max(0).round(6).tolist()]
        elif context.startswith('thirdperson_'):
            np.testing.assert_allclose(center, [0, 0, -1.5], atol=.00002)
            assert max(hi - lo) <= 4.50002
        bounds[context] = [lo.round(6).tolist(), hi.round(6).tolist()]
    return {'id': record['id'], 'elements': len(originals), 'facesChecked': faces_checked,
            'texturesByteIdentical': True, 'contextBoundsPixels': bounds,
            'firstPersonIdleBoundsNdc70Degrees': first_person_projection}, model


def raster_preview(models, path):
    from PIL import Image, ImageDraw
    tile, border = 280, 20
    result = Image.new('RGB', (tile * 5, (tile + 34) * 2 + 32), (27, 34, 45))
    draw = ImageDraw.Draw(result)
    for number, (ident, model) in enumerate(models):
        rgb = np.zeros((tile, tile, 4), dtype=np.uint8)
        rgb[:] = [35, 44, 57, 255]
        depth = np.full((tile, tile), -np.inf)
        textures = {key: np.asarray(Image.open(ASSETS / ('textures/' + value.split(':', 1)[1] + '.png')).convert('RGBA'))
                    for key, value in model['textures'].items() if key != 'particle'}
        for element in model['elements']:
            for side, face in element['faces'].items():
                world = geometry(element, FACE_CORNERS[side])
                points = displayed(world, model['display']['gui'])
                xy = points[:, :2] * ((tile - border * 2) / 16) + tile / 2
                xy[:, 1] = tile - xy[:, 1]
                u0, v0, u1, v1 = np.array(face['uv']) / 16
                uv = [(u0, v0), (u0, v1), (u1, v1), (u1, v0)]
                turn = face.get('rotation', 0) // 90
                uv = np.array(uv[turn:] + uv[:turn])
                texture = textures[face['texture'][1:]]
                for indices in ((0, 1, 2), (0, 2, 3)):
                    triangle, z, tex = xy[list(indices)], points[list(indices), 2], uv[list(indices)]
                    x0, x1 = max(0, int(np.floor(triangle[:, 0].min()))), min(tile - 1, int(np.ceil(triangle[:, 0].max())))
                    y0, y1 = max(0, int(np.floor(triangle[:, 1].min()))), min(tile - 1, int(np.ceil(triangle[:, 1].max())))
                    if x1 < x0 or y1 < y0:
                        continue
                    a, b, c = triangle
                    den = (b[1] - c[1]) * (a[0] - c[0]) + (c[0] - b[0]) * (a[1] - c[1])
                    if abs(den) < 1e-10:
                        continue
                    yy, xx = np.mgrid[y0:y1 + 1, x0:x1 + 1] + .5
                    wa = ((b[1] - c[1]) * (xx - c[0]) + (c[0] - b[0]) * (yy - c[1])) / den
                    wb = ((c[1] - a[1]) * (xx - c[0]) + (a[0] - c[0]) * (yy - c[1])) / den
                    wc = 1 - wa - wb
                    zz = wa * z[0] + wb * z[1] + wc * z[2]
                    mask = (wa >= -1e-8) & (wb >= -1e-8) & (wc >= -1e-8) & (zz >= depth[y0:y1 + 1, x0:x1 + 1])
                    tx = np.clip(((wa * tex[0, 0] + wb * tex[1, 0] + wc * tex[2, 0]) * texture.shape[1]).astype(int), 0, texture.shape[1] - 1)
                    ty = np.clip(((wa * tex[0, 1] + wb * tex[1, 1] + wc * tex[2, 1]) * texture.shape[0]).astype(int), 0, texture.shape[0] - 1)
                    pixels = texture[ty, tx]
                    mask &= pixels[:, :, 3] != 0
                    rgb[y0:y1 + 1, x0:x1 + 1][mask] = pixels[mask]
                    depth[y0:y1 + 1, x0:x1 + 1][mask] = zz[mask]
        x, y = (number % 5) * tile, (number // 5) * (tile + 34)
        result.paste(Image.fromarray(rgb).convert('RGB'), (x, y))
        draw.text((x + 8, y + tile + 8), ident, fill=(235, 239, 246))
    draw.text((10, (tile + 34) * 2 + 7), 'CPU geometry / texture audit. Final validation uses real Minecraft screenshots.', fill=(210, 218, 230))
    path.parent.mkdir(parents=True, exist_ok=True)
    result.save(path)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--report', type=Path, default=ROOT / 'verification/imported-items-0.9.3/source-fidelity.json')
    parser.add_argument('--preview', type=Path)
    args = parser.parse_args()
    records = load(ROOT / 'artwork/imports/item-models-manifest.json')['items']
    assert len(records) == 10 and len({r['id'] for r in records}) == 10
    checked = [verify(record) for record in records]
    report = {'passed': True, 'items': len(records), 'elements': sum(r['elements'] for r, _ in checked),
              'texturedFaces': sum(r['facesChecked'] for r, _ in checked), 'contexts': len(CONTEXTS) * len(records),
              'records': [r for r, _ in checked]}
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    if args.preview:
        raster_preview([(record['id'].split(':', 1)[1], model) for record, (_, model) in zip(records, checked)], args.preview)
    print(f'PASS: {report["items"]} items, {report["elements"]} source cubes, {report["texturedFaces"]} source faces, {report["contexts"]} fitted contexts; original PNG bytes preserved')


if __name__ == '__main__':
    main()
