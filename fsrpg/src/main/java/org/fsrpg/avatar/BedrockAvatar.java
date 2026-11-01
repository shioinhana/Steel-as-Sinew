package org.fsrpg.avatar;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.fsvoxel.render.Geometry;
import org.joml.Matrix4f;

import static org.fsrpg.avatar.AvatarGeometry.*;

/**
 * Static cuboid appearance from Bedrock geometry, without executing its animation or Molang.
 * Coordinate/UV conversion follows Blockbench's Bedrock importer and cube box-UV layout:
 * https://github.com/JannisX11/blockbench/blob/master/js/formats/bedrock/bedrock.js
 * https://github.com/JannisX11/blockbench/blob/master/js/outliner/types/cube.js
 * Schema: https://learn.microsoft.com/minecraft/creator/reference/content/visualreference/geometry.v1.21.0
 * Bedrock's X axis is reflected into the same Y-up space as BlockbenchAvatar; bone names are unchanged.
 */
public final class BedrockAvatar {
    private static final int MAX_CUBES = 16384;
    private static final Set<String> BONE_KEYS = Set.of("name", "parent", "pivot", "rotation", "mirror", "inflate",
        "cubes", "locators", "debug", "reset", "render_group_id");
    private static final Set<String> CUBE_KEYS = Set.of("name", "origin", "size", "pivot", "rotation", "inflate", "mirror", "uv");
    private static final Set<String> GEOMETRY_KEYS = Set.of("description", "bones", "texturewidth", "textureheight",
        "visible_bounds_width", "visible_bounds_height", "visible_bounds_offset", "debug");
    private static final Set<String> DESCRIPTION_KEYS = Set.of("identifier", "texture_width", "texture_height",
        "visible_bounds_width", "visible_bounds_height", "visible_bounds_offset");

    private BedrockAvatar() {}

    /** A missing identifier selects the sole geometry only; ambiguous files always require a choice. */
    public static AvatarDocument parse(Path geometry, Path texture, String geometryIdentifier) throws IOException {
        JsonObject root = readJson(geometry, MAX_JSON_BYTES);
        var choices = geometries(root);
        String requested = geometryIdentifier == null ? "" : geometryIdentifier.trim();
        if (requested.isEmpty() && choices.size() != 1)
            throw invalid("Bedrock file contains multiple geometries; specify geometryIdentifier: " + choices.keySet());
        JsonObject selected = requested.isEmpty() ? choices.values().iterator().next() : choices.get(requested);
        if (selected == null) throw invalid("Bedrock geometry not found: " + requested);
        onlyKeys(selected, GEOMETRY_KEYS, "geometry");
        JsonObject description = selected.has("description") ? object(selected.get("description"), "geometry.description") : null;
        if (description != null) onlyKeys(description, DESCRIPTION_KEYS, "geometry.description");
        int width = dimension(description, "texture_width", dimension(selected, "texturewidth", 64));
        int height = dimension(description, "texture_height", dimension(selected, "textureheight", 64));
        byte[] png = readPng(texture);

        JsonArray source = array(selected.get("bones"), "geometry.bones");
        if (source.isEmpty() || source.size() > 1024) throw invalid("Bedrock geometry must contain 1..1024 bones");
        var byName = new LinkedHashMap<String, SourceBone>();
        int cubeCount = 0;
        for (JsonElement element : source) {
            JsonObject bone = object(element, "bone");
            onlyKeys(bone, BONE_KEYS, "bone");
            String name = string(bone.get("name"), "bone.name");
            String key = name.toLowerCase(Locale.ROOT);
            String parent = bone.has("parent") ? string(bone.get("parent"), "bone.parent").toLowerCase(Locale.ROOT) : "";
            if (bool(bone, "reset", false)) throw invalid("Bedrock bone.reset is not supported: " + name);
            if (bone.has("render_group_id") && number(bone.get("render_group_id"), "render_group_id") != 0)
                throw invalid("Bedrock bone.render_group_id is not supported: " + name);
            float[] pivot = position(bone, "pivot");
            float[] rotation = rotation(bone);
            boolean mirror = bool(bone, "mirror", false);
            float inflate = optionalNumber(bone, "inflate", 0);
            var cubes = new ArrayList<JsonObject>();
            if (bone.has("cubes")) for (JsonElement cube : array(bone.get("cubes"), "bone.cubes")) {
                if (++cubeCount > MAX_CUBES) throw invalid("Bedrock avatar exceeds " + MAX_CUBES + " cubes");
                cubes.add(object(cube, "cube"));
            }
            if (byName.putIfAbsent(key, new SourceBone(name, parent, pivot, rotation, mirror, inflate, cubes)) != null)
                throw invalid("Duplicate Bedrock bone name: " + name);
        }
        var result = new ArrayList<AvatarDocument.Bone>();
        var indices = new HashMap<String, Integer>();
        var visiting = new java.util.HashSet<String>();
        for (String key : byName.keySet()) appendBone(key, byName, indices, visiting, result, width, height);
        if (result.stream().flatMap(b -> b.meshes().values().stream()).allMatch(m -> m.faces().isEmpty()))
            throw invalid("Bedrock geometry has no visible textured faces");
        return new AvatarDocument(result, List.of(new AvatarDocument.Texture(texture.getFileName().toString(), png)));
    }

    private static LinkedHashMap<String, JsonObject> geometries(JsonObject root) {
        var choices = new LinkedHashMap<String, JsonObject>();
        if (root.has("minecraft:geometry")) {
            for (JsonElement element : array(root.get("minecraft:geometry"), "minecraft:geometry")) {
                JsonObject value = object(element, "geometry");
                JsonObject desc = object(value.get("description"), "geometry.description");
                addGeometry(choices, string(desc.get("identifier"), "geometry.description.identifier"), value);
            }
        }
        for (var entry : root.entrySet()) if (entry.getKey().startsWith("geometry.")) {
            // Legacy skin packs can express geometry inheritance in the key; it needs merging first.
            if (entry.getKey().indexOf(':') >= 0) throw invalid("Inherited Bedrock geometries must be flattened before importing: " + entry.getKey());
            addGeometry(choices, entry.getKey(), object(entry.getValue(), entry.getKey()));
        }
        if (choices.isEmpty()) throw invalid("No Bedrock geometry found (expected minecraft:geometry or geometry.<name>)");
        return choices;
    }

    private static void addGeometry(Map<String, JsonObject> choices, String id, JsonObject value) {
        if (choices.putIfAbsent(id, value) != null) throw invalid("Duplicate Bedrock geometry identifier: " + id);
    }

    private record SourceBone(String name, String parent, float[] pivot, float[] rotation,
                              boolean mirror, float inflate, List<JsonObject> cubes) {}

    private static int appendBone(String key, Map<String, SourceBone> sources, Map<String, Integer> indices,
                                  Set<String> visiting, List<AvatarDocument.Bone> result, int width, int height) {
        Integer existing = indices.get(key);
        if (existing != null) return existing;
        SourceBone bone = sources.get(key);
        if (bone == null) throw invalid("Unknown Bedrock parent bone: " + key);
        if (!visiting.add(key)) throw invalid("Cyclic Bedrock bone hierarchy: " + bone.name());
        int parent = bone.parent().isEmpty() ? -1 : appendBone(bone.parent(), sources, indices, visiting, result, width, height);
        float[] parentPivot = parent < 0 ? new float[3] : sources.get(bone.parent()).pivot();
        var local = new Matrix4f().translation((bone.pivot()[0] - parentPivot[0]) / 16,
            (bone.pivot()[1] - parentPivot[1]) / 16, (bone.pivot()[2] - parentPivot[2]) / 16)
            .rotateZYX(radians(bone.rotation()[2]), radians(bone.rotation()[1]), radians(bone.rotation()[0]));
        var faces = new LinkedHashMap<Integer, List<Geometry.Face>>();
        for (JsonObject cube : bone.cubes()) cube(faces, cube, bone, width, height);
        int index = result.size();
        result.add(new AvatarDocument.Bone(bone.name(), parent, local, meshes(faces)));
        indices.put(key, index);
        visiting.remove(key);
        return index;
    }

    private static void cube(Map<Integer, List<Geometry.Face>> faces, JsonObject cube, SourceBone bone, int width, int height) {
        onlyKeys(cube, CUBE_KEYS, "cube");
        float[] origin = vector(cube.get("origin"), 3, "cube.origin");
        float[] size = vector(cube.get("size"), 3, "cube.size");
        for (float s : size) if (s < 0) throw invalid("Bedrock cube.size must be nonnegative");
        float[] from = {-(origin[0] + size[0]), origin[1], origin[2]};
        float[] to = {from[0] + size[0], from[1] + size[1], from[2] + size[2]};
        float[] pivot = position(cube, "pivot");
        float[] rotation = rotation(cube);
        float inflate = optionalNumber(cube, "inflate", bone.inflate());
        Map<String, FaceUv> uv = uv(cube.get("uv"), size, bool(cube, "mirror", bone.mirror()), width, height);
        addCube(faces, from, to, pivot, rotation, inflate, uv, bone.pivot());
    }

    private static Map<String, FaceUv> uv(JsonElement source, float[] size, boolean mirror, int width, int height) {
        var result = new LinkedHashMap<String, FaceUv>();
        if (source == null || source.isJsonArray()) {
            float[] offset = source == null ? new float[2] : vector(source, 2, "cube.uv");
            // Bedrock box UV dimensions use integral texel lengths, independently of cube geometry.
            float x = (float) Math.floor(size[0] + 1e-7), y = (float) Math.floor(size[1] + 1e-7), z = (float) Math.floor(size[2] + 1e-7);
            rect(result, "east", offset, 0, z, z, y, width, height);
            rect(result, "west", offset, z + x, z, z, y, width, height);
            rect(result, "up", offset, z + x, z, -x, -z, width, height);
            rect(result, "down", offset, z + 2 * x, 0, -x, z, width, height);
            rect(result, "south", offset, 2 * z + x, z, x, y, width, height);
            rect(result, "north", offset, z, z, x, y, width, height);
        } else {
            JsonObject values = object(source, "cube.uv");
            for (var entry : values.entrySet()) {
                String side = entry.getKey();
                if (!SIDES.contains(side)) throw invalid("Unknown Bedrock UV face: " + side);
                JsonObject face = object(entry.getValue(), "cube.uv." + side);
                onlyKeys(face, Set.of("uv", "uv_size", "uv_rotation", "material_instance"), "cube.uv." + side);
                if (face.has("material_instance") && !string(face.get("material_instance"), "material_instance").equals("default"))
                    throw invalid("Custom Bedrock face materials are not supported");
                float[] offset = vector(face.get("uv"), 2, "cube.uv." + side + ".uv");
                float[] extent = face.has("uv_size") ? vector(face.get("uv_size"), 2, "uv_size") : switch (side) {
                    case "east", "west" -> new float[]{size[2], size[1]};
                    case "up", "down" -> new float[]{size[0], size[2]};
                    default -> new float[]{size[0], size[1]};
                };
                float[] rectangle = {offset[0], offset[1], offset[0] + extent[0], offset[1] + extent[1]};
                if (side.equals("up") || side.equals("down"))
                    rectangle = new float[]{rectangle[2], rectangle[3], rectangle[0], rectangle[1]};
                float degrees = optionalNumber(face, "uv_rotation", 0);
                if (degrees != (int) degrees || degrees < 0 || degrees > 270 || degrees % 90 != 0)
                    throw invalid("Bedrock UV rotation must be 0, 90, 180 or 270");
                result.put(side, new FaceUv(0, rectangle, (int) degrees, width, height));
            }
        }
        if (mirror) {
            var reflected = new LinkedHashMap<String, FaceUv>();
            result.forEach((side, face) -> {
                float[] u = face.uv();
                String target = side.equals("east") ? "west" : side.equals("west") ? "east" : side;
                reflected.put(target, new FaceUv(0, new float[]{u[2], u[1], u[0], u[3]},
                    Math.floorMod(-face.rotation(), 360), width, height));
            });
            return reflected;
        }
        return result;
    }

    private static void rect(Map<String, FaceUv> values, String side, float[] offset,
                             float u, float v, float w, float h, int width, int height) {
        values.put(side, new FaceUv(0, new float[]{offset[0] + u, offset[1] + v,
            offset[0] + u + w, offset[1] + v + h}, 0, width, height));
    }

    private static float[] position(JsonObject object, String field) {
        float[] value = object.has(field) ? vector(object.get(field), 3, field) : new float[3];
        value[0] *= -1;
        return value;
    }

    private static float[] rotation(JsonObject object) {
        float[] value = object.has("rotation") ? vector(object.get("rotation"), 3, "rotation") : new float[3];
        value[0] *= -1;
        value[1] *= -1;
        return value;
    }

    private static int dimension(JsonObject object, String field, int fallback) {
        if (object == null || !object.has(field)) return fallback;
        float value = number(object.get(field), field);
        if (value < 1 || value > MAX_TEXTURE_SIZE || value != (int) value)
            throw invalid("Bedrock " + field + " must be an integer in 1..4096");
        return (int) value;
    }

    private static float optionalNumber(JsonObject object, String field, float fallback) {
        return object.has(field) ? number(object.get(field), field) : fallback;
    }

    private static boolean bool(JsonObject object, String field, boolean fallback) {
        if (!object.has(field)) return fallback;
        JsonElement element = object.get(field);
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) throw invalid(field + " must be boolean");
        return element.getAsBoolean();
    }

    private static String string(JsonElement value, String label) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
            || value.getAsString().isBlank() || value.getAsString().length() > 256)
            throw invalid(label + " must be a nonempty string of at most 256 characters");
        return value.getAsString();
    }

    private static JsonObject object(JsonElement value, String label) {
        if (value == null || !value.isJsonObject()) throw invalid(label + " must be an object");
        return value.getAsJsonObject();
    }

    private static JsonArray array(JsonElement value, String label) {
        if (value == null || !value.isJsonArray()) throw invalid(label + " must be an array");
        return value.getAsJsonArray();
    }

    private static void onlyKeys(JsonObject value, Set<String> supported, String label) {
        for (String key : value.keySet()) if (!supported.contains(key))
            throw invalid("Unsupported Bedrock " + label + " field: " + key);
    }
}
