package org.fsrpg.client;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.neoforged.fml.loading.FMLPaths;
import org.fsvoxel.render.Geometry;
import org.fsvoxel.render.ModelDocument;
import org.fsvoxel.render.ModelLoader;

/** Shared model cache for native actor animation and the timing editor. */
final class AnimationAssets {
    record Loaded(ModelDocument full, ModelDocument weapon, long modified) {}
    private static final Map<String, Loaded> cache = new HashMap<>();
    private AnimationAssets() {}
    static void clear() { cache.clear(); }
    static Loaded load(String name) throws Exception {
        Path directory = FMLPaths.CONFIGDIR.get().resolve("fsrpg/models").toAbsolutePath().normalize();
        Path path = directory.resolve(name).normalize();
        if (name.isBlank() || !path.startsWith(directory) || name.contains("\\") || name.contains(":"))
            throw new IllegalArgumentException("Invalid animation model path");
        if (!Files.exists(path)) {
            try (InputStream input = Minecraft.getInstance().getResourceManager().getResourceOrThrow(
                    Identifier.fromNamespaceAndPath("fsrpg", "fsvoxel/models/" + name)).open()) {
                String text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                Files.createDirectories(path.getParent()); Files.writeString(path, text, StandardCharsets.UTF_8);
            }
        }
        long modified = Files.getLastModifiedTime(path).toMillis();
        Loaded previous = cache.get(name);
        if (previous != null && previous.modified == modified) return previous;
        ModelDocument document = ModelLoader.load(path);
        var nodes = new java.util.LinkedHashMap<String, ModelDocument.Node>();
        boolean hasWeapon = false;
        for (var entry : document.nodes().entrySet()) {
            var node = entry.getValue();
            boolean weapon = node.id().equals("rapier") || node.id().startsWith("rapier_");
            hasWeapon |= weapon && !node.mesh().faces().isEmpty();
            nodes.put(entry.getKey(), new ModelDocument.Node(node.id(), node.parent(), node.visible(), weapon ? node.mesh() : Geometry.Mesh.EMPTY));
        }
        ModelDocument weapon = hasWeapon ? new ModelDocument(document.rig(), document.materials(), nodes, document.diagnostics()) : null;
        Loaded loaded = new Loaded(document, weapon, modified); cache.put(name, loaded); return loaded;
    }
}
