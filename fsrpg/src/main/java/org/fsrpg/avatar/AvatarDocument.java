package org.fsrpg.avatar;

import java.util.List;
import java.util.Map;
import org.fsvoxel.render.Geometry;
import org.joml.Matrix4f;

/** Client-independent appearance data. Coordinates are blocks, feet at zero, Y up. */
public record AvatarDocument(List<Bone> bones, List<Texture> textures) {
    public record Bone(String name, int parent, Matrix4f local, Map<Integer, Geometry.Mesh> meshes) {}
    public record Texture(String name, byte[] png) {}
    public AvatarDocument {
        bones = List.copyOf(bones); textures = List.copyOf(textures);
        if (bones.isEmpty() || bones.size() > 1024 || textures.isEmpty() || textures.size() > 32)
            throw new IllegalArgumentException("Avatar must contain 1..1024 bones and 1..32 textures");
        for (int i=0;i<bones.size();i++) {
            var b=bones.get(i);
            if(b.parent() < -1 || b.parent() >= i || !b.local().isFinite())
                throw new IllegalArgumentException("Invalid avatar bone hierarchy or transform: "+b.name());
            for(int texture:b.meshes().keySet()) if(texture<0 || texture>=textures.size())
                throw new IllegalArgumentException("Unknown avatar texture: "+texture);
        }
    }
    public List<Matrix4f> restWorld() {
        var result = new java.util.ArrayList<Matrix4f>();
        for(var bone:bones)result.add(bone.parent()<0?new Matrix4f(bone.local()):new Matrix4f(result.get(bone.parent())).mul(bone.local()));
        return result;
    }
}
