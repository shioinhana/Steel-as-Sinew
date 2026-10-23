"""Lossless Blockbench -> Minecraft 1.21.11 native static item export.

Geometry uses Rz*Ry*Rx Euler rotation; display uses Rx*Ry*Rz (ItemTransform).
"""
from pathlib import Path
import base64
import hashlib
import json
import math
import numpy as np

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'src/main/resources/assets/fsrpg'
CONTEXTS = ('gui', 'ground', 'fixed', 'head', 'firstperson_righthand',
            'firstperson_lefthand', 'thirdperson_righthand', 'thirdperson_lefthand', 'on_shelf')


def dump(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes((json.dumps(value, ensure_ascii=False, indent=2) + '\n').encode('utf-8'))


def rotation(angles, display=False):
    x, y, z = np.radians(angles)
    cx, sx, cy, sy, cz, sz = math.cos(x), math.sin(x), math.cos(y), math.sin(y), math.cos(z), math.sin(z)
    rx = np.array([[1, 0, 0], [0, cx, -sx], [0, sx, cx]])
    ry = np.array([[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]])
    rz = np.array([[cz, -sz, 0], [sz, cz, 0], [0, 0, 1]])
    return rx @ ry @ rz if display else rz @ ry @ rx


def visible_elements(data):
    """Honor export/visibility, including ancestors; reject lost transforms."""
    elements = {e['uuid']: e for e in data['elements']}
    groups = {g['uuid']: g for g in data.get('groups', [])}
    seen, eligible = set(), set()

    def visit(node, enabled=True):
        uid = node if isinstance(node, str) else node['uuid']
        value = elements.get(uid, groups.get(uid, node if isinstance(node, dict) else {}))
        enabled = enabled and value.get('export', True) and value.get('visibility', True)
        if uid in elements:
            if uid in seen:
                raise ValueError(f'Duplicate element in outliner: {uid}')
            seen.add(uid)
            if enabled:
                eligible.add(uid)
            return
        if enabled and any(value.get('rotation', [0, 0, 0])):
            raise ValueError(f'Rotated group requires explicit baking: {uid}')
        for child in node.get('children', []) if isinstance(node, dict) else []:
            visit(child, enabled)

    for node in data.get('outliner', []):
        visit(node)
    if not data.get('outliner'):
        eligible = {e['uuid'] for e in data['elements'] if e.get('export', True) and e.get('visibility', True)}
    elif seen != set(elements):
        raise ValueError(f'Outliner misses {len(set(elements) - seen)} elements')
    return [e for e in data['elements'] if e['uuid'] in eligible]


def cube_points(element):
    r = element.get('rotation', {})
    matrix = rotation([r.get(a, 0) for a in 'xyz'])
    origin = np.array(r.get('origin', [8, 8, 8]), dtype=float)
    scale = 1 / np.max(np.abs(matrix), axis=0) if r.get('rescale') else np.ones(3)
    return np.array([matrix @ ((np.array([x, y, z]) - origin) * scale) + origin
                     for x in (element['from'][0], element['to'][0])
                     for y in (element['from'][1], element['to'][1])
                     for z in (element['from'][2], element['to'][2])])


def rounded(values):
    return [0.0 if abs(float(v)) < .0000005 else round(float(v), 6) for v in values]


def display_transforms(points, profile):
    """Fit actual rotated bounds and correct each source's off-center geometry.

    Left translations compensate separately: Minecraft reflects X translation
    and Y/Z rotation when rendering left hands. Sizes are model pixels.
    """
    flat, pouch = profile == 'flat', profile == 'pouch'
    face = [90, 0, 0] if flat else [0, 0, 0]
    plans = {
        'gui': ([20, -30, 0] if pouch else face, 13.5, [0, 0, 0], 'screen'),
        'fixed': (face, 13, [0, 0, 0], 'screen'),
        'ground': ([90, 0, 0] if profile == 'upright_coin' else [0, 0, 0], 5, [0, 0, 0], 'ground'),
        'head': (face, 7, [0, 8, -7], 'screen'),
        'on_shelf': (face, 8, [0, 0, 0], 'screen'),
    }
    for hand in ('righthand', 'lefthand'):
        # ItemInHandRenderer adds (+/-0.56, -0.52, -0.72) blocks before
        # these transforms. Centering at the local origin clips ordinary
        # items at the screen's lower outer edge with the 70-degree hand FOV.
        # Move both hands inward/up and leave perspective/depth margins.
        anchor = [2 if hand == 'lefthand' else -2, 6, 0]
        plans['firstperson_' + hand] = ([0, -20, 0] if pouch else face, 5.5, anchor, 'screen')
        # Vanilla already rotates X by -90 degrees in its hand layer. Flat
        # sources hang upright; upright coins need the opposite rotation.
        plans['thirdperson_' + hand] = ([90, 0, 0] if profile in ('upright_coin', 'pouch') else [0, 0, 0], 4.5, [0, 0, -1.5], 'space')
    result = {}
    for context in CONTEXTS:
        angles, target, anchor, mode = plans[context]
        left = context.endswith('lefthand')
        applied_angles = [angles[0], -angles[1], -angles[2]] if left else angles
        transformed = (points - 8) @ rotation(applied_angles, display=True).T
        lo, hi = transformed.min(axis=0), transformed.max(axis=0)
        dimension = max((hi - lo)[:2]) if mode == 'screen' else max(hi - lo)
        scale = min(3.5, target / dimension)
        translation = np.array(anchor, dtype=float) - (lo + hi) * .5 * scale
        if mode == 'ground':
            translation[1] = .5 - lo[1] * scale
        if left:
            translation[0] *= -1
        result[context] = {'rotation': angles, 'translation': rounded(translation), 'scale': [round(scale, 6)] * 3}
    return result


def export_item(source, ident, label, profile, texture_override=None):
    source = Path(source)
    raw = source.read_bytes()
    data = json.loads(raw.decode('utf-8-sig'))
    texture_refs, texture_records = {}, []
    for i, texture in enumerate(data['textures']):
        png = base64.b64decode(texture['source'].split(',', 1)[1], validate=True)
        if not png.startswith(b'\x89PNG\r\n\x1a\n'):
            raise ValueError(f'{source}: texture {i} is not PNG')
        width, height = texture.get('uv_width', texture['width']), texture.get('uv_height', texture['height'])
        if width <= 0 or height <= 0:
            raise ValueError('Texture dimensions must be positive')
        target = ASSETS / f'textures/item/{ident}_{i}.png'
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(png)
        texture_refs[str(i)] = f'fsrpg:item/{ident}_{i}'
        texture_records.append({'id': f'fsrpg:textures/item/{ident}_{i}.png', 'width': width, 'height': height, 'sha256': hashlib.sha256(png).hexdigest()})
    elements = []
    selected = visible_elements(data)
    for original in selected:
        if original.get('type', 'cube') != 'cube':
            raise ValueError('Static native item export requires cuboids')
        inflate = original.get('inflate', 0)
        lo, hi = np.array(original['from'], dtype=float) - inflate, np.array(original['to'], dtype=float) + inflate
        if not np.all(np.isfinite([lo, hi])) or np.any(lo < -16) or np.any(hi > 32) or np.any(lo > hi):
            raise ValueError(f'Cube outside native model bounds: {original["uuid"]}')
        faces = {}
        for side, face in original['faces'].items():
            if face.get('texture') is None or face['texture'] == -1:
                continue
            index = int(face['texture']) if texture_override is None else texture_override
            texture = texture_records[index]
            uv = face['uv']
            faces[side] = {'uv': [uv[0] * 16 / texture['width'], uv[1] * 16 / texture['height'], uv[2] * 16 / texture['width'], uv[3] * 16 / texture['height']], 'texture': f'#{index}'}
            if face.get('rotation'):
                faces[side]['rotation'] = face['rotation']
            if face.get('tint', -1) >= 0:
                faces[side]['tintindex'] = face['tint']
        if not faces:
            continue
        cube = {'from': lo.tolist(), 'to': hi.tolist(), 'faces': faces}
        angles = original.get('rotation', [0, 0, 0])
        if any(angles):
            cube['rotation'] = {'origin': original.get('origin', [8, 8, 8]), **dict(zip('xyz', angles)), 'rescale': original.get('rescale', False)}
        if not original.get('shade', True):
            cube['shade'] = False
        elements.append(cube)
    if not elements:
        raise ValueError(f'{source}: no visible textured elements')
    points = np.concatenate([cube_points(e) for e in elements])
    texture_refs['particle'] = next(iter(elements[0]['faces'].values()))['texture']
    model = {'textures': texture_refs, 'elements': elements, 'display': display_transforms(points, profile), 'gui_light': 'front'}
    dump(ASSETS / f'models/item/{ident}.json', model)
    dump(ASSETS / f'items/{ident}.json', {'model': {'type': 'minecraft:model', 'model': f'fsrpg:item/{ident}'}})
    return {
        'id': f'fsrpg:{ident}', 'label': label,
        'source': source.relative_to(ROOT).as_posix(), 'sourceSha256': hashlib.sha256(raw).hexdigest(),
        'elements': len(elements), 'sourceElements': len(data['elements']),
        'hiddenOrUnexportedElements': len(data['elements']) - len(selected),
        'groups': len(data.get('groups', [])), 'rootGroups': sum(isinstance(n, dict) for n in data.get('outliner', [])),
        'faces': sum(len(e['faces']) for e in elements), 'textures': texture_records,
        'textureOverride': texture_override, 'boundsPixels': [rounded(points.min(axis=0)), rounded(points.max(axis=0))],
        'profile': profile, 'displayContexts': list(CONTEXTS),
    }
