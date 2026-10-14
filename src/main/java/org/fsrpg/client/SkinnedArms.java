package org.fsrpg.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import org.fsvoxel.client.mixin.ModelPartAccess;
import org.fsvoxel.client.model.MinecraftMeshRenderer;
import org.fsvoxel.render.Geometry;
import org.joml.Vector3f;

/** Original skin/armor UVs, centered on common joint axes with volume-preserving section rotation. */
public final class SkinnedArms {
    private record Actor(HumanoidModel<?> model, PlayerAnimations.Frame frame) {}
    private record Context(Actor actor) {}
    private record Skin(Geometry.Mesh mesh, float centerX) {}
    private static final Map<Model<?>, Actor> actors = new WeakHashMap<>();
    private static final Map<ModelPart, Skin> meshes = new IdentityHashMap<>();
    private static final Map<ModelPart, Skin> legMeshes = new IdentityHashMap<>();
    private static final Map<ModelPart, Skin> torsoMeshes = new IdentityHashMap<>();
    private static final ArrayDeque<Context> contexts = new ArrayDeque<>();
    private static long renderedSegments;
    private static long renderedLegSections;
    private static long renderedTorsoSections;
    private SkinnedArms() {}
    public static long renderedSegments() { return renderedSegments; }
    public static long renderedLegSections() { return renderedLegSections; }
    public static long renderedTorsoSections() {return renderedTorsoSections;}
    static void clear() { actors.clear(); meshes.clear(); legMeshes.clear(); torsoMeshes.clear(); contexts.clear(); }
    static void prepare(HumanoidModel<?> model, PlayerAnimations.Frame frame) {
        actors.remove(model);
        if (frame != null && frame.world().containsKey("right_forearm") && frame.world().containsKey("left_forearm"))
            actors.put(model, new Actor(model, frame));
    }
    public static void begin(Model<?> model) { contexts.push(new Context(actors.get(model))); }
    public static void end() { if (!contexts.isEmpty()) contexts.pop(); }
    public static boolean render(ModelPart part, PoseStack poses, VertexConsumer consumer, int light, int overlay, int tint) {
        if (contexts.isEmpty() || contexts.peek().actor == null) return false;
        Actor actor = contexts.peek().actor;
        boolean connectedWaist=actor.frame.supportedLower()!=null&&actor.frame.model().rig().id().equals("fsrpg.spear");
        if(part==actor.model.body&&connectedWaist) {
            drawTorso(part,new TorsoDeformation(actor.frame.world().get("body"),actor.frame.supportedLower()),poses,consumer,light,overlay,tint);
            return true;
        }
        boolean rightLeg = part == actor.model.rightLeg, leftLeg = part == actor.model.leftLeg;
        if (rightLeg || leftLeg) {
            float flexion = rightLeg ? actor.frame.rightKnee() : actor.frame.leftKnee();
            if (flexion <= .0001f&&!connectedWaist) return false;
            drawLeg(part, new LegDeformation(part.x, part.y, part.z, part.xRot, part.yRot, part.zRot, flexion, actor.frame.supportedLower() != null,
                    connectedWaist?actor.frame.supportedLower().pelvisYaw():Float.NaN), poses, consumer, light, overlay, tint);
            return true;
        }
        String side = part == actor.model.rightArm ? "right" : part == actor.model.leftArm ? "left" : "";
        if (side.isEmpty()) return false;
        if (!part.visible) return true;
        draw(part, side, actor.frame, poses, consumer, light, overlay, tint);
        return true;
    }
    private static void drawTorso(ModelPart part,TorsoDeformation torso,PoseStack poses,VertexConsumer consumer,int light,int overlay,int tint) {
        if(!part.visible)return;
        Skin skin=torsoMeshes.computeIfAbsent(part,p->split(p,TorsoDeformation.SECTION_PLANES));
        if(!part.skipDraw){MinecraftMeshRenderer.prepare(torso.deform(skin.mesh),poses.last(),tint,light,overlay).submit(consumer);renderedTorsoSections+=14;}
        for(ModelPart child:((ModelPartAccess)(Object)part).fsvoxel$children().values())drawTorso(child,torso,poses,consumer,light,overlay,tint);
    }
    private static void drawLeg(ModelPart part, LegDeformation leg, PoseStack poses, VertexConsumer consumer, int light, int overlay, int tint) {
        if (!part.visible) return;
        Skin skin = legMeshes.computeIfAbsent(part, p -> split(p, LegDeformation.SECTION_PLANES));
        if (!part.skipDraw) {
            MinecraftMeshRenderer.prepare(leg.deform(skin.mesh), poses.last(), tint, light, overlay).submit(consumer);
            renderedLegSections += 2;
        }
        for (ModelPart child : ((ModelPartAccess)(Object)part).fsvoxel$children().values())
            drawLeg(child, leg, poses, consumer, light, overlay, tint);
    }
    private static void draw(ModelPart part, String side, PlayerAnimations.Frame frame, PoseStack poses,
                             VertexConsumer consumer, int light, int overlay, int tint) {
        if (!part.visible) return;
        Skin skin = meshes.computeIfAbsent(part, SkinnedArms::split);
        if (!part.skipDraw) {
            Geometry.Mesh mesh = new ArmDeformation(frame.world(), side).deform(skin.mesh, skin.centerX);
            MinecraftMeshRenderer.prepare(mesh, poses.last(), tint, light, overlay).submit(consumer);
            renderedSegments += 3;
        }
        // Skin sleeves use their own original UVs and deformation; armor models follow this same path.
        for (ModelPart child : ((ModelPartAccess) (Object) part).fsvoxel$children().values())
            draw(child, side, frame, poses, consumer, light, overlay, tint);
    }
    static float centerX(ModelPart part) {
        var cubes = ((ModelPartAccess) (Object) part).fsvoxel$cubes();
        return cubes.isEmpty() ? 0 : (cubes.getFirst().minX + cubes.getFirst().maxX) / 2;
    }
    private static Skin split(ModelPart part) {
        return split(part, ArmDeformation.SECTION_PLANES);
    }
    private static Skin split(ModelPart part, float[] planes) {
        List<Geometry.Face> faces = new ArrayList<>();
        for (ModelPart.Cube cube : ((ModelPartAccess) (Object) part).fsvoxel$cubes()) {
            for (ModelPart.Polygon polygon : cube.polygons) {
                List<Geometry.Vertex> vertices = new ArrayList<>();
                Vector3f normal = new Vector3f(polygon.normal());
                for (ModelPart.Vertex vertex : polygon.vertices())
                    vertices.add(new Geometry.Vertex(vertex.x(), vertex.y(), vertex.z(), vertex.u(), vertex.v(), normal.x, normal.y, normal.z));
                if (Math.abs(normal.y) > .9f) add(faces, vertices);
                else {
                    float previous = Float.NEGATIVE_INFINITY;
                    for (float plane : planes) {
                        add(faces, clip(clip(vertices, previous, false), plane, true)); previous = plane;
                    }
                    add(faces, clip(vertices, previous, false));
                }
            }
        }
        return new Skin(new Geometry.Mesh(faces), centerX(part));
    }
    private static void add(List<Geometry.Face> faces, List<Geometry.Vertex> vertices) {
        if (vertices.size() == 3) {
            List<Geometry.Vertex> quad = new ArrayList<>(vertices); quad.add(vertices.getLast());
            faces.add(new Geometry.Face("skin", quad));
        } else if (vertices.size() == 4) faces.add(new Geometry.Face("skin", vertices));
    }
    private static List<Geometry.Vertex> clip(List<Geometry.Vertex> input, float plane, boolean less) {
        if (input.isEmpty()) return List.of();
        List<Geometry.Vertex> out = new ArrayList<>();
        Geometry.Vertex previous = input.getLast(); boolean previousIn = less ? previous.y() <= plane : previous.y() >= plane;
        for (Geometry.Vertex vertex : input) {
            boolean inside = less ? vertex.y() <= plane : vertex.y() >= plane;
            if (inside != previousIn) {
                float fraction = (plane - previous.y()) / (vertex.y() - previous.y());
                out.add(new Geometry.Vertex(previous.x() + (vertex.x() - previous.x()) * fraction, plane,
                        previous.z() + (vertex.z() - previous.z()) * fraction,
                        previous.u() + (vertex.u() - previous.u()) * fraction, previous.v() + (vertex.v() - previous.v()) * fraction,
                        vertex.nx(), vertex.ny(), vertex.nz()));
            }
            if (inside) out.add(vertex);
            previous = vertex; previousIn = inside;
        }
        return out;
    }
}
