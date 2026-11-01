package org.fsrpg.client;

import java.util.List;
import java.util.Map;
import org.fsvoxel.render.Geometry;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Checks real skin/armor dimensions and joint geometry, independently of render counters. */
public final class ArmDeformationTest {
    private static int checks;
    private static final Matrix4f NATIVE = new Matrix4f().translation(0, 1.5f, 0).scale(.01f, -.01f, -.01f);
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
    private static void close(Vector3f actual, Vector3f expected, String message) {
        check(actual.distance(expected) < .00002f, message + ": " + actual + " expected " + expected);
    }
    private static Map<String, Matrix4f> bones(String side, float centerPixels, float upperX, float elbowZ, float wristX, float wristY) {
        float sign = side.equals("right") ? -1 : 1;
        Matrix4f shoulder = new Matrix4f().translation(sign * centerPixels * 6.25f, 150, 0).rotateX(upperX);
        Matrix4f elbow = new Matrix4f(shoulder).translate(0, -37.5f, 0).rotateZ(elbowZ);
        Matrix4f hand = new Matrix4f(elbow).translate(0, -25, 0).rotateX(wristX).rotateY(wristY);
        return Map.of(side + "_arm", shoulder, side + "_forearm", elbow, side + "_hand", hand);
    }
    private static void neutral() {
        for (String side : List.of("right", "left")) for (boolean slim : new boolean[]{false, true}) {
            float sign = side.equals("right") ? -1 : 1;
            float skinCenter = sign * (slim ? .5f : 1), boneCenter = slim ? 5.5f : 6;
            ArmDeformation geometry = new ArmDeformation(bones(side, boneCenter, 0, 0, 0, 0), side);
            for (boolean armor : new boolean[]{false, true}) {
                float center = armor ? sign : skinCenter;
                float halfWidth = armor ? 3 : slim ? 1.5f : 2;
                float inflation = armor ? 1 : 0;
                for (float x : new float[]{center - halfWidth, center, center + halfWidth})
                    for (float y : new float[]{-2 - inflation, 0, 3.5f, 4, 4.5f, 7.5f, 8, 8.5f, 9, 10 + inflation})
                        for (float z : new float[]{-2 - inflation, 0, 2 + inflation}) {
                            Vector3f expected = new Vector3f((sign * boneCenter + x - center) / 16, (y + 2) / 16, z / 16);
                            close(geometry.point(x, y, z, center), expected, "neutral " + side + " slim=" + slim + " armor=" + armor);
                        }
            }
            check(Math.abs(geometry.point(skinCenter, -2, 0, skinCenter).distance(geometry.point(skinCenter, 10, 0, skinCenter)) - .75f) < .00001,
                    "neutral skin arm is exactly 12 native pixels");
        }
    }
    private static void articulation() {
        for (String side : List.of("right", "left")) for (boolean slim : new boolean[]{false, true}) {
            float sign = side.equals("right") ? -1 : 1, center = sign * (slim ? .5f : 1);
            float skinHalf = slim ? 1.5f : 2;
            for (float bend : new float[]{0, .7f, 1.5f, 2.8f}) for (float twist : new float[]{0, 1.2f, (float)Math.PI}) {
                var world = bones(side, slim ? 5.5f : 6, -.7f, bend, -.9f, twist);
                ArmDeformation geometry = new ArmDeformation(world, side);
                for (float y : new float[]{-1, 3.5f, 3.75f, 4, 4.25f, 4.5f, 7.5f, 7.75f, 8, 8.25f, 8.5f, 9, 10}) {
                    float width = geometry.point(center - skinHalf, y, 0, center).distance(geometry.point(center + skinHalf, y, 0, center));
                    check(Math.abs(width - skinHalf / 8) < .00002f, "skin section width preserved through bend and 180 degree wrist twist");
                    Vector3f axis = geometry.point(center, y, 0, center);
                    Vector3f skin = geometry.point(center + skinHalf, y, 0, center);
                    Vector3f armor = geometry.point(sign + 3, y, 0, sign);
                    check(Math.abs(axis.distance(armor) - 3f / 16) < .00002f, "armor section radius is original 3 pixels");
                    check(axis.distance(armor) > axis.distance(skin), "armor consistently surrounds default and slim skin");
                }
                for (float edge : new float[]{3.5f, 4.5f, 7.5f, 8.5f})
                    close(geometry.point(center + skinHalf, edge - .000001f, 1, center), geometry.point(center + skinHalf, edge + .000001f, 1, center), "joint band has no discontinuity");
                Vector3f palm = geometry.point(center, 9, 0, center);
                Vector3f grip = NATIVE.transformPosition(world.get(side + "_hand").transformPosition(new Vector3f(0, -6.25f, 0)));
                close(palm, grip, "palm center follows wrist rotation around source y8");
                Matrix4f sword = new Matrix4f(world.get(side + "_hand")).translate(0, -12.5f, 0).rotateX((float)Math.PI);
                Vector3f hiltCenter = NATIVE.transformPosition(sword.transformPosition(new Vector3f(0, -6.25f, 0)));
                close(palm, hiltCenter, "actual hilt center coincides with palm");
            }
        }
    }
    private static void uv() {
        var vertices = List.of(new Geometry.Vertex(-3, 3.75f, -2, .125f, .25f, 0, 0, -1),
                new Geometry.Vertex(1, 3.75f, -2, .1875f, .25f, 0, 0, -1),
                new Geometry.Vertex(1, 4, -2, .1875f, .265625f, 0, 0, -1),
                new Geometry.Vertex(-3, 4, -2, .125f, .265625f, 0, 0, -1));
        Geometry.Mesh mesh = new Geometry.Mesh(List.of(new Geometry.Face("skin", vertices)));
        var transformed = new ArmDeformation(bones("right", 6, -.7f, 1.5f, -.9f, 2), "right").deform(mesh, -1);
        for (int i = 0; i < 4; i++) {
            var actual = transformed.faces().getFirst().vertices().get(i);
            var original = vertices.get(i);
            check(actual.u() == original.u() && actual.v() == original.v(), "original UV unchanged");
            check(Float.isFinite(actual.x()) && Float.isFinite(actual.y()) && Float.isFinite(actual.z()), "finite deformed point");
            check(Math.abs(actual.normal().length() - 1) < .00001, "unit section normal");
        }
    }
    private static Vector3f sectionAxis(ArmDeformation geometry, float center, float y) {
        return geometry.point(center + 2, y, 0, center).sub(geometry.point(center - 2, y, 0, center)).normalize();
    }
    private static void cuffPronation() {
        for (String side : List.of("right", "left")) {
            float center = side.equals("right") ? -1 : 1;
            for (float twist : new float[]{1.2f, 2.2f, (float) Math.PI}) {
                var geometry = new ArmDeformation(bones(side, 6, -.4f, 1.1f, 0, twist), side);
                Vector3f handAxis = sectionAxis(geometry, center, 9);
                for (float y : new float[]{7.5f, 7.75f, 8, 8.25f, 8.5f})
                    check(sectionAxis(geometry, center, y).dot(handAxis) > .99999f,
                            "pure forearm pronation never twists the short wrist cuff: " + side + " " + twist + " y=" + y);
                check(sectionAxis(geometry, center, 6).dot(sectionAxis(geometry, center, 4.5f)) < .9f,
                        "pronation is visibly distributed along the forearm rather than hidden at the hand");
            }
            var geometry = new ArmDeformation(bones(side, 6, -.4f, 1.1f, .4f, 2.2f), side);
            for (float y : new float[]{3.5f, 4.5f, 7.5f, 8.5f}) {
                float step = .002f;
                Vector3f before = geometry.point(center + 2, y, 2, center)
                        .sub(geometry.point(center + 2, y - step, 2, center)).normalize();
                Vector3f after = geometry.point(center + 2, y + step, 2, center)
                        .sub(geometry.point(center + 2, y, 2, center)).normalize();
                check(before.dot(after) > .999f, "cuff and elbow surface tangent has no sharp interpolation seam at " + y);
            }
        }
    }
    private static Map<String, Matrix4f> pronatedBones(String side, float boneCenter, float bend, float twist,
                                                       boolean atForearm) {
        float sign = side.equals("right") ? -1 : 1;
        Matrix4f shoulder = new Matrix4f().translation(sign * boneCenter * 6.25f, 150, 0)
                .rotateXYZ(-.4f, .5f * sign, -.2f * sign);
        Matrix4f elbow = new Matrix4f(shoulder).translate(0, -37.5f, 0).rotateX(bend);
        if (atForearm) elbow.rotateY(twist);
        Matrix4f hand = new Matrix4f(elbow).translate(0, -25, 0);
        if (!atForearm) hand.rotateY(twist);
        return Map.of(side + "_arm", shoulder, side + "_forearm", elbow, side + "_hand", hand);
    }
    private static void authoredForearmPronation() {
        for (String side : List.of("right", "left")) for (boolean slim : new boolean[]{false, true}) {
            float sign = side.equals("right") ? -1 : 1, boneCenter = slim ? 5.5f : 6;
            for (float bend : new float[]{-.35f, -1.1f, -1.9f}) for (float twist : new float[]{-.52f, .52f}) {
                var world = pronatedBones(side, boneCenter, bend, twist, true);
                var authored = new ArmDeformation(world, side);
                var atHand = new ArmDeformation(pronatedBones(side, boneCenter, bend, twist, false), side);
                var noTwist = new ArmDeformation(pronatedBones(side, boneCenter, bend, 0, true), side);
                for (boolean armor : new boolean[]{false, true}) {
                    float center = sign * (armor || !slim ? 1 : .5f), radius = armor ? 3 : slim ? 1.5f : 2;
                    for (float y : new float[]{-2, 3.5f, 3.75f, 4, 4.25f, 4.5f, 5, 5.5f, 6, 6.5f, 7, 7.5f, 8, 8.5f, 9, 10}) {
                        for (float x : new float[]{center - radius, center, center + radius})
                            for (float z : new float[]{-radius, 0, radius}) {
                                close(authored.point(x, y, z, center), atHand.point(x, y, z, center),
                                        "same anatomical pose has the same surface whether twist is authored at elbow or hand");
                                if (y <= 4.5f) close(authored.point(x, y, z, center), noTwist.point(x, y, z, center),
                                        "forearm pronation does not twist the upper arm or elbow band");
                            }
                        close(authored.point(center, y, 0, center), noTwist.point(center, y, 0, center),
                                "pronation preserves the whole arm centerline for stock/slim skin and armor");
                        check(Math.abs(authored.point(center - radius, y, 0, center)
                                        .distance(authored.point(center + radius, y, 0, center)) - radius / 8) < .00002f,
                                "authored forearm pronation preserves each skin and armor section width");
                    }
                    check(sectionAxis(authored, center, 6).dot(sectionAxis(noTwist, center, 6)) < .99f,
                            "mid-forearm carries a visible share of the authored twist");
                    close(sectionAxis(authored, center, 7.5f), sectionAxis(authored, center, 9),
                            "pronation finishes before the wrist cuff");
                    Vector3f shoulder = authored.point(center, -2, 0, center), elbow = authored.point(center, 4, 0, center),
                            wrist = authored.point(center, 8, 0, center), palm = authored.point(center, 9, 0, center);
                    check(Math.abs(shoulder.distance(elbow) - .375f) < .00002f, "upper arm remains 37.5 cm");
                    check(Math.abs(elbow.distance(wrist) - .25f) < .00002f, "forearm remains 25 cm");
                    check(Math.abs(wrist.distance(palm) - .0625f) < .00002f, "fist center remains 6.25 cm from wrist");
                    close(palm, NATIVE.transformPosition(world.get(side + "_hand").transformPosition(new Vector3f(0, -6.25f, 0))),
                            "redistributing twist keeps the exact authored grip for stock/slim skin and armor");
                }
            }
        }
    }
    private static void surfaceNormals() {
        var geometry = new ArmDeformation(bones("right", 6, -.4f, 1.1f, .4f, 2.2f), "right");
        float center = -1, step = .002f;
        for (float y : new float[]{3.75f, 4.1f, 5, 5.5f, 6, 6.5f, 7, 7.75f, 8, 8.25f}) {
            var source = new Geometry.Vertex(center + 2, y, -2, .25f, .5f, 0, 0, -1);
            var mesh = new Geometry.Mesh(List.of(new Geometry.Face("skin", List.of(source, source, source, source))));
            var actual = geometry.deform(mesh, center).faces().getFirst().vertices().getFirst().normal();
            var dx = geometry.point(center + 2 + step, y, -2, center)
                    .sub(geometry.point(center + 2 - step, y, -2, center)).normalize();
            var dy = geometry.point(center + 2, y + step, -2, center)
                    .sub(geometry.point(center + 2, y - step, -2, center)).normalize();
            check(Math.abs(actual.dot(dx)) < .002f && Math.abs(actual.dot(dy)) < .002f,
                    "deformed sleeve normal follows both actual surface tangents at " + y);
        }
    }
    private static void sharedFrameCache() {
        var world = bones("right", 6, -.4f, 1.1f, .4f, 2.2f);
        var skin = new ArmDeformation(world, "right");
        var armor = new ArmDeformation(world, "right");
        var a = new Geometry.Vertex(-2, 3.75f, -2, .25f, .5f, 0, 0, -1);
        var b = new Geometry.Vertex(2, 6.5f, -2, .75f, .5f, 0, 0, -1);
        var source = new Geometry.Mesh(List.of(new Geometry.Face("skin", List.of(a, b, b, a))));
        var actualSkin = skin.deform(source, 0).faces().getFirst().vertices();
        check(skin.cachedSectionCount() == 2 && armor.cachedSectionCount() == 2,
                "one frame shares its two computed affine sections across skin and armor instances");
        var shiftedA = new Geometry.Vertex(a.x() + 1, a.y(), a.z(), a.u(), a.v(), a.nx(), a.ny(), a.nz());
        var shiftedB = new Geometry.Vertex(b.x() + 1, b.y(), b.z(), b.u(), b.v(), b.nx(), b.ny(), b.nz());
        var shiftedMesh = new Geometry.Mesh(List.of(new Geometry.Face("armor", List.of(shiftedA, shiftedB, shiftedB, shiftedA))));
        var actualArmor = armor.deform(shiftedMesh, 1).faces().getFirst().vertices();
        check(armor.cachedSectionCount() == 2, "repeated mesh rendering never recomputes an already cached section");
        for (int i = 0; i < actualSkin.size(); i++) {
            var first = actualSkin.get(i); var second = actualArmor.get(i);
            close(new Vector3f(second.x(), second.y(), second.z()), new Vector3f(first.x(), first.y(), first.z()),
                    "shared section cache respects each source mesh center");
            close(second.normal(), first.normal(), "shared analytic Jacobian respects each source mesh center");
        }
        skin.point(0, 7, 0, 0);
        check(armor.cachedSectionCount() == 3, "new section remains shared across all layers of the frame");
    }
    public static void main(String[] args) {
        neutral(); articulation(); uv(); cuffPronation(); authoredForearmPronation(); surfaceNormals(); sharedFrameCache();
        System.out.println("ArmDeformationTest passed: " + checks + " checks (stock/slim/armor, pronation, C1 cuff seams, wrist/palm/hilt, surface normals, UV)");
    }
}
