package org.fsrpg.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Map;
import org.fsvoxel.render.Geometry;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Rigid cross sections, distributed forearm pronation, and analytic surface normals. */
public final class ArmDeformation {
    public static final float PIXEL_CM = 6.25f;
    public static final float[] SECTION_PLANES = {3.5f, 3.75f, 4, 4.25f, 4.5f, 5, 5.5f, 6, 6.5f, 7, 7.5f, 7.75f, 8, 8.25f, 8.5f};
    // A sampled Frame owns immutable world matrices. Skin, overlay and armor all reuse it.
    // Identity avoids expensive Matrix4f content hashing; the bound also releases old frames.
    private static final Map<Map<String, Matrix4f>, CachedPose[]> FRAME_CACHE = new IdentityHashMap<>();
    private final CachedPose pose;

    public ArmDeformation(Map<String, Matrix4f> world, String side) {
        if (!side.equals("right") && !side.equals("left")) throw new IllegalArgumentException("Unknown arm side " + side);
        CachedPose[] arms = FRAME_CACHE.get(world);
        if (arms == null) {
            if (FRAME_CACHE.size() >= 64) FRAME_CACHE.clear();
            arms = new CachedPose[2]; FRAME_CACHE.put(world, arms);
        }
        int index = side.equals("right") ? 0 : 1;
        if (arms[index] == null) arms[index] = new CachedPose(world, side);
        pose = arms[index];
    }
    int cachedSectionCount() { return pose.count; }

    /** Angular velocity of a shortest-path quaternion slerp, before its easing derivative. */
    private static final class Band {
        final Quaternionf start, end;
        final float from, width, joint, wx, wy, wz;
        Band(Quaternionf first, Quaternionf last, float from, float width, float joint) {
            this.start = new Quaternionf(first); this.end = new Quaternionf(last);
            this.from = from; this.width = width; this.joint = joint;
            if (start.dot(end) < 0) end.set(-end.x, -end.y, -end.z, -end.w);
            Quaternionf change = new Quaternionf(end).mul(new Quaternionf(start).invert()).normalize();
            float length = (float) Math.sqrt(change.x * change.x + change.y * change.y + change.z * change.z);
            float angular = length < 1e-7f ? 0 : 2 * (float) Math.atan2(length, change.w) / length;
            wx = change.x * angular; wy = change.y * angular; wz = change.z * angular;
        }
    }

    /** Native coordinates are affine in source x/z at each y, including their y derivative. */
    private static final class Section {
        final float y, cx, cy, cz, xx, xy, xz, zx, zy, zz;
        final float dcx, dcy, dcz, dxx, dxy, dxz, dzx, dzy, dzz;
        final float yx, yy, yz;
        Section(float y, Vector3f center, Vector3f x, Vector3f z, Vector3f centerDerivative,
                Vector3f xDerivative, Vector3f zDerivative, Vector3f rigidY) {
            this.y = y;
            cx = center.x * .01f; cy = 1.5f - center.y * .01f; cz = -center.z * .01f;
            xx = x.x * .01f; xy = -x.y * .01f; xz = -x.z * .01f;
            zx = z.x * .01f; zy = -z.y * .01f; zz = -z.z * .01f;
            dcx = centerDerivative.x * .01f; dcy = -centerDerivative.y * .01f; dcz = -centerDerivative.z * .01f;
            dxx = xDerivative.x * .01f; dxy = -xDerivative.y * .01f; dxz = -xDerivative.z * .01f;
            dzx = zDerivative.x * .01f; dzy = -zDerivative.y * .01f; dzz = -zDerivative.z * .01f;
            yx = rigidY.x * .01f; yy = -rigidY.y * .01f; yz = -rigidY.z * .01f;
        }
        Vector3f point(float x, float z) {
            return new Vector3f(cx + xx * x + zx * z, cy + xy * x + zy * z, cz + xz * x + zz * z);
        }
        Geometry.Vertex vertex(Geometry.Vertex source, float centeredX) {
            float z = source.z();
            float px = cx + xx * centeredX + zx * z, py = cy + xy * centeredX + zy * z, pz = cz + xz * centeredX + zz * z;
            float dyx = dcx + dxx * centeredX + dzx * z;
            float dyy = dcy + dxy * centeredX + dzy * z;
            float dyz = dcz + dxz * centeredX + dzz * z;
            // Cofactor(J) transforms normals, including the changing section rotation.
            float nx = source.nx() * (dyy * zz - dyz * zy) + source.ny() * (zy * xz - zz * xy) + source.nz() * (xy * dyz - xz * dyy);
            float ny = source.nx() * (dyz * zx - dyx * zz) + source.ny() * (zz * xx - zx * xz) + source.nz() * (xz * dyx - xx * dyz);
            float nz = source.nx() * (dyx * zy - dyy * zx) + source.ny() * (zx * xy - zy * xx) + source.nz() * (xx * dyy - xy * dyx);
            float lengthSquared = nx * nx + ny * ny + nz * nz;
            if (lengthSquared < 1e-12f) {
                nx = source.nx() * xx + source.ny() * yx + source.nz() * zx;
                ny = source.nx() * xy + source.ny() * yy + source.nz() * zy;
                nz = source.nx() * xz + source.ny() * yz + source.nz() * zz;
                lengthSquared = nx * nx + ny * ny + nz * nz;
            }
            float inverse = 1 / (float) Math.sqrt(lengthSquared);
            return new Geometry.Vertex(px, py, pz, source.u(), source.v(), nx * inverse, ny * inverse, nz * inverse);
        }
    }

    private static final class CachedPose {
        final Matrix4f shoulder;
        final Vector3f elbowPoint, handPoint;
        final Band elbowBand, forearmBand, wristBand;
        Section[] sections = new Section[24];
        int count;
        Section last;
        CachedPose(Map<String, Matrix4f> world, String side) {
            shoulder = world.get(side + "_arm");
            Matrix4f elbow = world.get(side + "_forearm"), hand = world.get(side + "_hand");
            Quaternionf upper = shoulder.getUnnormalizedRotation(new Quaternionf()).normalize();
            Quaternionf lower = elbow.getUnnormalizedRotation(new Quaternionf()).normalize();
            Quaternionf handRotation = hand.getUnnormalizedRotation(new Quaternionf()).normalize();
            // Authors may place pronation on the forearm or on the hand. Separate
            // the forearm's axial twist from its elbow swing so both representations
            // distribute that twist along the forearm, not inside the short elbow band.
            // Right-multiplying out local Y twist preserves the forearm's axis and
            // every joint/grip center, including rigs with a non-X elbow bend.
            Quaternionf elbowRelative = new Quaternionf(upper).invert().mul(lower);
            Quaternionf elbowTwist = axialTwist(elbowRelative);
            Quaternionf untwistedLower = new Quaternionf(lower).mul(new Quaternionf(elbowTwist).invert());
            Quaternionf relative = new Quaternionf(lower).invert().mul(handRotation);
            Quaternionf twist = axialTwist(relative);
            Quaternionf pronated = new Quaternionf(lower).mul(twist);
            elbowBand = new Band(upper, untwistedLower, 3.5f, 1, 4);
            forearmBand = new Band(untwistedLower, pronated, 4.5f, 3, 4);
            wristBand = new Band(pronated, handRotation, 7.5f, 1, 8);
            elbowPoint = elbow.getTranslation(new Vector3f()); handPoint = hand.getTranslation(new Vector3f());
        }
        private static Quaternionf axialTwist(Quaternionf rotation) {
            float length = rotation.y * rotation.y + rotation.w * rotation.w;
            return length < 1e-8f ? new Quaternionf() : new Quaternionf(0, rotation.y, 0, rotation.w).normalize();
        }
        Section section(float y) {
            if (last != null && last.y == y) return last;
            for (int i = 0; i < count; i++) if (sections[i].y == y) return last = sections[i];
            if (count == sections.length) sections = Arrays.copyOf(sections, count * 2);
            return last = sections[count++] = create(y);
        }
        Section create(float y) {
            if (y < 3.5f) {
                Vector3f center = shoulder.transformPosition(new Vector3f(0, -(y + 2) * PIXEL_CM, 0));
                Vector3f x = shoulder.transformDirection(new Vector3f(PIXEL_CM, 0, 0));
                Vector3f z = shoulder.transformDirection(new Vector3f(0, 0, -PIXEL_CM));
                Vector3f rigidY = shoulder.transformDirection(new Vector3f(0, -PIXEL_CM, 0));
                return new Section(y, center, x, z, rigidY, new Vector3f(), new Vector3f(), rigidY);
            }
            Band band = y <= 4.5f ? elbowBand : y < 7.5f ? forearmBand : wristBand;
            float t = Math.clamp((y - band.from) / band.width, 0, 1);
            float eased = t * t * (3 - 2 * t), derivative = 6 * t * (1 - t) / band.width;
            Quaternionf rotation = new Quaternionf(band.start).slerp(band.end, eased);
            Vector3f omega = new Vector3f(band.wx * derivative, band.wy * derivative, band.wz * derivative);
            Vector3f offset = rotation.transform(new Vector3f(0, -(y - band.joint) * PIXEL_CM, 0));
            Vector3f center = new Vector3f(offset).add(y < 7.5f ? elbowPoint : handPoint);
            Vector3f x = rotation.transform(new Vector3f(PIXEL_CM, 0, 0));
            Vector3f z = rotation.transform(new Vector3f(0, 0, -PIXEL_CM));
            Vector3f rigidY = rotation.transform(new Vector3f(0, -PIXEL_CM, 0));
            return new Section(y, center, x, z, new Vector3f(omega).cross(offset).add(rigidY),
                    new Vector3f(omega).cross(x), new Vector3f(omega).cross(z), rigidY);
        }
    }

    public Vector3f point(float x, float y, float z, float sourceCenterX) {
        return pose.section(y).point(x - sourceCenterX, z);
    }

    public Geometry.Mesh deform(Geometry.Mesh source, float sourceCenterX) {
        var faces = new ArrayList<Geometry.Face>(source.faces().size());
        for (var face : source.faces()) {
            var vertices = new ArrayList<Geometry.Vertex>(face.vertices().size());
            for (var vertex : face.vertices()) vertices.add(pose.section(vertex.y()).vertex(vertex, vertex.x() - sourceCenterX));
            faces.add(new Geometry.Face(face.material(), vertices));
        }
        return new Geometry.Mesh(faces);
    }
}
