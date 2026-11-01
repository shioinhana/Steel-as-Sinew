package org.fsrpg.avatar;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.fsvoxel.render.Geometry;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Bounded source-file loading and cube geometry shared by avatar importers. */
public final class AvatarGeometry {
    public static final long MAX_JSON_BYTES = 24L * 1024 * 1024;
    public static final int MAX_PNG_BYTES = 8 * 1024 * 1024;
    public static final int MAX_TEXTURE_SIZE = 4096;
    public static final List<String> SIDES = List.of("down", "up", "north", "south", "west", "east");
    private static final int[][][] CORNERS = {
        {{0,0,1},{0,0,0},{1,0,0},{1,0,1}},
        {{0,1,0},{0,1,1},{1,1,1},{1,1,0}},
        {{1,1,0},{1,0,0},{0,0,0},{0,1,0}},
        {{0,1,1},{0,0,1},{1,0,1},{1,1,1}},
        {{0,1,0},{0,0,0},{0,0,1},{0,1,1}},
        {{1,1,1},{1,0,1},{1,0,0},{1,1,0}}
    };
    private AvatarGeometry() {}

    public static float number(JsonElement value, String label) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw invalid(label + " must be a number");
        double number = value.getAsDouble();
        if (!Double.isFinite(number) || Math.abs(number) > 1_000_000)
            throw invalid(label + " must be finite and within +/-1000000");
        return (float) number;
    }

    public static float[] vector(JsonElement value, int length, String label) {
        if (value == null || !value.isJsonArray() || value.getAsJsonArray().size() != length)
            throw invalid(label + " must have " + length + " numbers");
        float[] result = new float[length];
        for (int i=0; i<length; i++) result[i] = number(value.getAsJsonArray().get(i), label + "[" + i + "]");
        return result;
    }

    public static JsonObject readJson(Path path, long maxBytes) throws IOException {
        if (maxBytes <= 0 || maxBytes > MAX_JSON_BYTES) throw invalid("Invalid JSON size bound");
        byte[] bytes = boundedBytes(path, maxBytes);
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.startsWith("\ufeff")) text = text.substring(1);
        JsonElement data;
        try { data = JsonParser.parseString(text); }
        catch (RuntimeException | StackOverflowError error) { throw invalid("Invalid avatar JSON: " + path.getFileName()); }
        if (!data.isJsonObject()) throw invalid("Avatar JSON must be an object");
        return data.getAsJsonObject();
    }

    private static byte[] boundedBytes(Path path, long maximum) throws IOException {
        if (!Files.isRegularFile(path) || Files.size(path) <= 0 || Files.size(path) > maximum)
            throw invalid("Missing, empty or oversized avatar asset: " + path.getFileName());
        try (var input=Files.newInputStream(path)) {
            byte[] bytes=input.readNBytes((int)maximum+1);
            if (bytes.length==0 || bytes.length>maximum) throw invalid("Oversized avatar asset: " + path.getFileName());
            return bytes;
        }
    }

    /** Resolve existing relative files, including symlinks, within the model's own directory. */
    public static Path relativeFile(Path model, String relative) throws IOException {
        if (relative == null || relative.isBlank() || relative.indexOf(':') >= 0 || relative.indexOf('\0') >= 0)
            throw invalid("Texture path must be relative to the model directory");
        Path part;
        try { part=Path.of(relative.replace('\\','/')); }
        catch (RuntimeException error) { throw invalid("Invalid avatar texture path"); }
        if (part.isAbsolute()) throw invalid("Absolute avatar texture paths are not supported");
        for (Path segment:part) if (segment.toString().equals("..")) throw invalid("Avatar texture path cannot contain '..'");
        Path directory=model.toAbsolutePath().getParent().toRealPath();
        Path resolved=directory.resolve(part).normalize();
        if (!resolved.startsWith(directory)) throw invalid("Avatar texture path escapes model directory");
        resolved=resolved.toRealPath();
        if (!resolved.startsWith(directory)) throw invalid("Avatar texture symlink escapes model directory");
        return resolved;
    }

    public static byte[] readPng(Path path) throws IOException {
        byte[] bytes=boundedBytes(path,MAX_PNG_BYTES); pngSize(bytes); return bytes;
    }

    public static byte[] embeddedPng(String source) throws IOException {
        String prefix="data:image/png;base64,";
        if (source == null || !source.startsWith(prefix) || source.length() > prefix.length()+((MAX_PNG_BYTES+2L)/3)*4)
            throw invalid("Embedded avatar texture must be a PNG of at most 8 MiB");
        byte[] bytes;
        try { bytes=Base64.getDecoder().decode(source.substring(prefix.length())); }
        catch (IllegalArgumentException error) { throw invalid("Invalid avatar PNG base64"); }
        pngSize(bytes); return bytes;
    }

    /** Validate dimensions before decoding to avoid an unbounded allocation in the image reader. */
    public static int[] pngSize(byte[] bytes) throws IOException {
        byte[] signature={(byte)137,80,78,71,13,10,26,10};
        if (bytes==null || bytes.length<24 || bytes.length>MAX_PNG_BYTES) throw invalid("Invalid or oversized avatar PNG");
        for(int i=0;i<signature.length;i++) if(bytes[i]!=signature[i]) throw invalid("Avatar texture is not a PNG");
        try(var stream=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers=ImageIO.getImageReadersByFormatName("png");
            if(!readers.hasNext()) throw invalid("PNG reader is unavailable");
            var reader=readers.next();
            try {
                reader.setInput(stream,true,true);
                int width=reader.getWidth(0),height=reader.getHeight(0);
                if(width<1 || height<1 || width>MAX_TEXTURE_SIZE || height>MAX_TEXTURE_SIZE)
                    throw invalid("Avatar PNG dimensions must be 1..4096");
                if(reader.read(0)==null) throw invalid("Invalid avatar PNG pixels");
                return new int[]{width,height};
            } finally {reader.dispose();}
        }
    }

    /** UV rectangle is in source texture pixels; face rotation is in quarter turns, in degrees. */
    public record FaceUv(int texture, float[] uv, int rotation, int width, int height) {
        public FaceUv {
            if(texture<0 || uv==null || uv.length!=4 || rotation%90!=0 || width<1 || height<1)
                throw invalid("Invalid avatar cube face UV");
            uv=uv.clone();
            for(float v:uv) if(!Float.isFinite(v)) throw invalid("Nonfinite avatar UV");
        }
    }

    /** Source coordinates and pivots are pixels; Euler angles use Rz * Ry * Rx, in degrees. */
    public static void addCube(Map<Integer,List<Geometry.Face>> faces, float[] from, float[] to,
                               float[] origin, float[] rotation, float inflate,
                               Map<String,FaceUv> uv, float[] parentOrigin) {
        var transform=new Matrix4f().translation(origin[0],origin[1],origin[2])
            .rotateZYX(radians(rotation[2]),radians(rotation[1]),radians(rotation[0]))
            .translate(-origin[0],-origin[1],-origin[2]);
        float[] lo=from.clone(),hi=to.clone();
        for(int i=0;i<3;i++) {lo[i]-=inflate;hi[i]+=inflate;}
        addTransformedCube(faces,lo,hi,transform,uv,parentOrigin);
    }

    /** Transform is expressed in pixels, before conversion into blocks and parent-pivot space. */
    public static void addTransformedCube(Map<Integer,List<Geometry.Face>> faces, float[] from, float[] to,
                                          Matrix4f transform, Map<String,FaceUv> uv, float[] parentOrigin) {
        if(!transform.isFinite()) throw invalid("Nonfinite avatar cube transform");
        for(int i=0;i<3;i++) if(!Float.isFinite(from[i]) || !Float.isFinite(to[i]) || to[i]<from[i])
            throw invalid("Avatar cube has inverted or nonfinite bounds");
        for(int side=0;side<SIDES.size();side++) {
            String name=SIDES.get(side);FaceUv face=uv.get(name);if(face==null)continue;
            List<Vector3f> points=new ArrayList<>();
            for(var corner:CORNERS[side]) points.add(transform.transformPosition(new Vector3f(
                corner[0]==0?from[0]:to[0],corner[1]==0?from[1]:to[1],corner[2]==0?from[2]:to[2])));
            Vector3f normal=new Vector3f(points.get(1)).sub(points.get(0)).cross(new Vector3f(points.get(2)).sub(points.get(0)));
            if(normal.lengthSquared()<1e-12f)continue;
            normal.normalize();
            float[][] coords={{face.uv()[0],face.uv()[1]},{face.uv()[0],face.uv()[3]},
                              {face.uv()[2],face.uv()[3]},{face.uv()[2],face.uv()[1]}};
            int turn=Math.floorMod(face.rotation()/90,4);
            List<Geometry.Vertex> vertices=new ArrayList<>();
            for(int i=0;i<4;i++) {
                Vector3f p=points.get(i);float[] tex=coords[(i+turn)%4];
                if(!p.isFinite())throw invalid("Nonfinite avatar cube vertex");
                vertices.add(new Geometry.Vertex((p.x-parentOrigin[0])/16,(p.y-parentOrigin[1])/16,(p.z-parentOrigin[2])/16,
                    tex[0]/face.width(),tex[1]/face.height(),normal.x,normal.y,normal.z));
            }
            faces.computeIfAbsent(face.texture(),k->new ArrayList<>()).add(new Geometry.Face(name,List.copyOf(vertices)));
        }
    }

    public static Map<Integer,Geometry.Mesh> meshes(Map<Integer,List<Geometry.Face>> faces) {
        var result=new java.util.LinkedHashMap<Integer,Geometry.Mesh>();
        faces.forEach((texture,quads)->result.put(texture,new Geometry.Mesh(List.copyOf(quads))));return Map.copyOf(result);
    }
    public static float radians(float degrees) {return (float)Math.toRadians(degrees);}
    public static IllegalArgumentException invalid(String message) {return new IllegalArgumentException(message);}
}
