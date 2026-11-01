package org.fsrpg.avatar;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import org.fsvoxel.render.Geometry;
import org.joml.Vector3f;

/** Independent fixed coordinate/UV expectations for skin-pack geometry, without a game client. */
public final class BedrockAvatarTest {
    private static int checks;
    private static Path directory, texture;
    private static final String CUBE = "{\"origin\":[0,0,0],\"size\":[2,4,6],\"uv\":[3,5]}";

    public static void main(String[] args) throws Exception {
        directory = Files.createTempDirectory("fsrpg-bedrock-avatar-");
        texture = directory.resolve("asymmetric.png");
        var image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (int y=0;y<64;y++) for (int x=0;x<64;x++) image.setRGB(x,y,0xff000000 | x*4<<16 | y*4<<8 | (x<32?0x33:0xcc));
        ImageIO.write(image,"PNG",texture.toFile());
        legacyAndModernAgree();
        identifiersAreExplicit();
        officialPlayerSidesAndHierarchy();
        hierarchyAndRotation();
        cubePivotInflationAndRotation();
        inheritedMirrorAndCubeOverride();
        perFaceUvAndRotation();
        boxUvDimensionsAreTexels();
        unsupportedAndMalformedFail();
        validatePngAndDimensions();
        if (args.length>0) officialFixture(Path.of(args[0]));
        System.out.println("BedrockAvatarTest passed: " + checks + " checks");
    }

    private static void legacyAndModernAgree() throws Exception {
        String bones = "[{\"name\":\"body\",\"pivot\":[0,12,0],\"cubes\":["+CUBE+"]}]";
        var modern = parse(modern(bones));
        var legacy = parse("{\"format_version\":\"1.10.0\",\"geometry.test\":{\"texturewidth\":64,\"textureheight\":64,\"bones\":"+bones+"}}");
        check(modern.bones().size()==1 && legacy.bones().size()==1,"legacy and modern both parse");
        check(modern.bones().getFirst().meshes().equals(legacy.bones().getFirst().meshes()),"legacy and modern geometry/UV identical");
        check(modern.textures().getFirst().png().length==Files.size(texture),"source PNG bytes preserved");
        close(12f/16,modern.restWorld().getFirst().m31(),"global bone pivot");
        bounds(modern,0,new float[]{-2f/16,0,0},new float[]{0,4f/16,6f/16});
        faceUv(modern,0,"east",new float[]{3,11,9,15});
        faceUv(modern,0,"west",new float[]{11,11,17,15});
        faceUv(modern,0,"north",new float[]{9,11,11,15});
        faceUv(modern,0,"south",new float[]{17,11,19,15});
        faceUv(modern,0,"up",new float[]{11,11,9,5});
        faceUv(modern,0,"down",new float[]{13,5,11,11});
        for (var face:modern.bones().getFirst().meshes().get(0).faces()) {
            var a=face.vertices().get(0);var b=face.vertices().get(1);var c=face.vertices().get(2);
            Vector3f cross=b.position().sub(a.position()).cross(c.position().sub(a.position())).normalize();
            check(cross.dot(a.normal())>.9999,"outward face winding agrees with normal: "+face.material());
        }
    }

    private static void identifiersAreExplicit() throws Exception {
        JsonObject root=JsonParser.parseString(modern("[{\"name\":\"body\",\"cubes\":["+CUBE+"]}]")).getAsJsonObject();
        JsonObject second=root.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject().deepCopy();
        second.getAsJsonObject("description").addProperty("identifier","geometry.second");
        second.getAsJsonArray("bones").get(0).getAsJsonObject().addProperty("name","alternate");
        root.getAsJsonArray("minecraft:geometry").add(second);
        reject(root.toString(),"", "multiple geometries");
        reject(root.toString(),"geometry.missing", "geometry not found");
        var selected=parse(root.toString(),"geometry.second");
        check(selected.bones().getFirst().name().equals("alternate"),"requested geometry selected rather than first entry");
        second.getAsJsonObject("description").addProperty("identifier","geometry.test");
        reject(root.toString(),"geometry.test","Duplicate Bedrock geometry");
        reject("{\"geometry.test:geometry.humanoid\":{\"bones\":[]}}","", "Inherited");
        reject("{}","", "No Bedrock geometry");
    }

    private static void officialPlayerSidesAndHierarchy() throws Exception {
        // Values from Mojang/bedrock-samples humanoid.custom.geo.json; parent order is deliberately unsorted.
        var model=parse(modern("""
            [{"name":"rightArm","parent":"body","pivot":[-5,22,0],"cubes":[{"origin":[-8,12,-2],"size":[4,12,4],"uv":[40,16]}]},
             {"name":"leftArm","parent":"body","pivot":[5,22,0],"cubes":[{"origin":[4,12,-2],"size":[4,12,4],"uv":[32,48]}]},
             {"name":"body","parent":"waist","pivot":[0,24,0]},
             {"name":"waist","parent":"root","pivot":[0,12,0]},
             {"name":"root","pivot":[0,0,0]}]
            """));
        int right=bone(model,"rightArm"),left=bone(model,"leftArm");
        close(5f/16,model.restWorld().get(right).m30(),"right arm retains positive Blockbench X");
        close(-5f/16,model.restWorld().get(left).m30(),"left arm retains negative Blockbench X");
        close(22f/16,model.restWorld().get(right).m31(),"unsorted hierarchy preserves global pivot");
        bounds(model,right,new float[]{4f/16,12f/16,-2f/16},new float[]{8f/16,24f/16,2f/16});
        bounds(model,left,new float[]{-8f/16,12f/16,-2f/16},new float[]{-4f/16,24f/16,2f/16});
        faceUv(model,right,"north",new float[]{44,20,48,32});
        faceUv(model,left,"north",new float[]{36,52,40,64});
        for(int i=0;i<model.bones().size();i++)check(model.bones().get(i).parent()<i,"parents precede children");
    }

    private static void hierarchyAndRotation() throws Exception {
        var model=parse(modern("""
            [{"name":"child","parent":"BODY","pivot":[-5,22,0],"cubes":[{"origin":[-8,12,-2],"size":[4,12,4],"uv":[0,0]}]},
             {"name":"body","pivot":[0,12,0],"rotation":[0,0,90]}]
            """));
        Vector3f pivot=model.restWorld().get(bone(model,"child")).getTranslation(new Vector3f());
        close(-10f/16,pivot.x,"parent Z rotation affects child X");
        close(17f/16,pivot.y,"parent Z rotation affects child Y");
        model=parse(modern("[{\"name\":\"body\",\"rotation\":[90,0,0],\"cubes\":["+CUBE+"]}]"));
        Vector3f y=model.restWorld().getFirst().transformDirection(new Vector3f(0,1,0));
        close(-1,y.z,"Bedrock X Euler sign converted");
        model=parse(modern("[{\"name\":\"body\",\"rotation\":[0,90,0],\"cubes\":["+CUBE+"]}]"));
        Vector3f z=model.restWorld().getFirst().transformDirection(new Vector3f(0,0,1));
        close(-1,z.x,"Bedrock Y Euler sign converted");
    }

    private static void cubePivotInflationAndRotation() throws Exception {
        var model=parse(modern("[{\"name\":\"body\",\"inflate\":1,\"cubes\":["+CUBE.replace("\"uv\":[3,5]","\"uv\":[3,5],\"pivot\":[0,0,0],\"rotation\":[90,0,0]")+"]}]"));
        bounds(model,0,new float[]{-3f/16,-1f/16,-5f/16},new float[]{1f/16,7f/16,1f/16});
        var overriding=parse(modern("[{\"name\":\"body\",\"inflate\":1,\"cubes\":["+CUBE.replace("\"uv\":[3,5]","\"uv\":[3,5],\"inflate\":0")+"]}]"));
        bounds(overriding,0,new float[]{-2f/16,0,0},new float[]{0,4f/16,6f/16});
        var pivoted=parse(modern("[{\"name\":\"body\",\"cubes\":["+CUBE.replace("\"uv\":[3,5]","\"uv\":[3,5],\"pivot\":[-2,0,0],\"rotation\":[0,0,90]")+"]}]"));
        bounds(pivoted,0,new float[]{-2f/16,-4f/16,0},new float[]{2f/16,-2f/16,6f/16});
    }

    private static void inheritedMirrorAndCubeOverride() throws Exception {
        var mirrored=parse(modern("[{\"name\":\"body\",\"mirror\":true,\"cubes\":["+CUBE+"]}]"));
        faceUv(mirrored,0,"east",new float[]{17,11,11,15});
        faceUv(mirrored,0,"west",new float[]{9,11,3,15});
        faceUv(mirrored,0,"north",new float[]{11,11,9,15});
        bounds(mirrored,0,new float[]{-2f/16,0,0},new float[]{0,4f/16,6f/16});
        var overriding=parse(modern("[{\"name\":\"body\",\"mirror\":true,\"cubes\":["+CUBE.replace("\"uv\":[3,5]","\"uv\":[3,5],\"mirror\":false")+"]}]"));
        faceUv(overriding,0,"east",new float[]{3,11,9,15});
        faceUv(overriding,0,"north",new float[]{9,11,11,15});
    }

    private static void perFaceUvAndRotation() throws Exception {
        String cube="""
            {"origin":[0,0,0],"size":[2,4,6],"uv":{
              "north":{"uv":[4,8],"uv_size":[-2,4],"uv_rotation":90},
              "up":{"uv":[10,20],"uv_size":[2,6]},
              "west":{"uv":[1,2]}}}
            """;
        var model=parse(modern("[{\"name\":\"body\",\"cubes\":["+cube+"]}]"));
        check(model.bones().getFirst().meshes().get(0).faces().size()==3,"omitted per-face UV faces are not rendered");
        var north=face(model,0,"north").vertices();
        close(4f/64,north.get(0).u(),"90 degree UV starts at bottom left U");
        close(12f/64,north.get(0).v(),"90 degree UV starts at bottom left V");
        close(2f/64,north.get(1).u(),"negative UV extent preserved");
        faceUv(model,0,"up",new float[]{12,26,10,20});
        faceUv(model,0,"west",new float[]{1,2,7,6});
        // Official mirror semantics: flip U and exchange the east/west faces, without moving the box.
        cube=cube.replace("\"size\":[2,4,6]","\"size\":[2,4,6],\"mirror\":true");
        var mirror=parse(modern("[{\"name\":\"body\",\"cubes\":["+cube+"]}]"));
        check(mirror.bones().getFirst().meshes().get(0).faces().stream().anyMatch(f->f.material().equals("east")),"per-face mirror exchanges west/east");
        check(mirror.bones().getFirst().meshes().get(0).faces().stream().noneMatch(f->f.material().equals("west")),"per-face mirror preserves omitted face");
    }

    private static void boxUvDimensionsAreTexels() throws Exception {
        var model=parse(modern("[{\"name\":\"body\",\"cubes\":["+CUBE.replace("[2,4,6]","[2.5,4.75,6.5]")+"]}]"));
        faceUv(model,0,"north",new float[]{9,11,11,15});
        bounds(model,0,new float[]{-2.5f/16,0,0},new float[]{0,4.75f/16,6.5f/16});
    }

    private static void unsupportedAndMalformedFail() throws Exception {
        String base=modern("[{\"name\":\"body\",\"cubes\":["+CUBE+"]}]");
        for(String field:List.of("\"poly_mesh\":{}","\"texture_meshes\":[]","\"bind_pose_rotation\":[0,0,0]","\"binding\":\"q.foo\"","\"reset\":true","\"render_group_id\":1"))
            reject(base.replace("\"name\":\"body\"","\"name\":\"body\","+field),"",field.substring(1,field.indexOf('"',1)));
        reject(base.replace("\"size\":[2,4,6]","\"size\":[-2,4,6]"),"","nonnegative");
        reject(base.replace("\"size\":[2,4,6]","\"size\":[\"2\",4,6]"),"","must be a number");
        reject(base.replace("\"uv\":[3,5]","\"uv\":{\"banana\":{\"uv\":[0,0]}}"),"","Unknown Bedrock UV face");
        reject(base.replace("\"uv\":[3,5]","\"uv\":{\"north\":{\"uv\":[0,0],\"uv_rotation\":45}}"),"","UV rotation");
        reject(base.replace("\"uv\":[3,5]","\"uv\":{\"north\":{\"uv\":[0,0],\"material_instance\":\"emissive\"}}"),"","materials");
        reject(base.replace("\"name\":\"body\"","\"name\":\"body\",\"parent\":\"missing\""),"","Unknown Bedrock parent");
        reject(base.replace("\"name\":\"body\"","\"name\":\"body\",\"parent\":\"body\""),"","Cyclic");
        reject(modern("[{\"name\":\"body\"},{\"name\":\"BODY\"}]"),"","Duplicate Bedrock bone");
        reject(modern("[]"),"","1..1024");
        reject(modern("[{\"name\":\"body\"}]"),"","no visible");
        reject(base.replace("\"uv\":[3,5]","\"uv\":[3,5],\"inflate\":-4"),"","inverted");
        reject(base.replace("\"uv\":[3,5]","\"uv\":[3,5],\"mirror\":1"),"","must be boolean");
        reject(base.replace("\"origin\":[0,0,0]","\"origin\":[1e300,0,0]"),"","finite");
        reject(base.replace("\"uv\":[3,5]","\"uv\":[3,5],\"rescale\":true"),"","rescale");
        var plane=parse(base.replace("\"size\":[2,4,6]","\"size\":[2,4,0]"));
        check(plane.bones().getFirst().meshes().get(0).faces().size()==2,"zero-depth plane has two valid faces");
    }

    private static void validatePngAndDimensions() throws Exception {
        String base=modern("[{\"name\":\"body\",\"cubes\":["+CUBE+"]}]");
        reject(base.replace("\"texture_width\":64","\"texture_width\":0"),"","texture_width");
        reject(base.replace("\"texture_width\":64","\"texture_width\":1.5"),"","texture_width");
        Path invalid=directory.resolve("invalid.png");Files.writeString(invalid,"not a png");
        Path path=directory.resolve("invalidtexture.geo.json");Files.writeString(path,base);
        boolean rejected=false;
        try{BedrockAvatar.parse(path,invalid,"");}catch(IllegalArgumentException expected){rejected=true;}
        check(rejected,"bad PNG rejected");
    }

    private static void officialFixture(Path path) throws Exception {
        var model=BedrockAvatar.parse(path,texture,"geometry.humanoid.custom");
        check(model.bones().size()==17,"official Mojang humanoid bones preserved");
        int right=bone(model,"rightArm"),left=bone(model,"leftArm");
        close(5f/16,model.restWorld().get(right).m30(),"official fixture right arm");
        close(-5f/16,model.restWorld().get(left).m30(),"official fixture left arm");
        check(model.bones().stream().flatMap(b->b.meshes().values().stream()).mapToInt(m->m.faces().size()).sum()==72,"official fixture all 12 cubes rendered");
    }

    private static AvatarDocument parse(String json) throws Exception{return parse(json,"");}
    private static AvatarDocument parse(String json,String id) throws Exception {
        Path path=directory.resolve("geometry.json");Files.writeString(path,json,StandardCharsets.UTF_8);
        return BedrockAvatar.parse(path,texture,id);
    }
    private static String modern(String bones) {
        return "{\"format_version\":\"1.21.0\",\"minecraft:geometry\":[{\"description\":{\"identifier\":\"geometry.test\",\"texture_width\":64,\"texture_height\":64},\"bones\":"+bones+"}]}";
    }
    private static int bone(AvatarDocument model,String name){for(int i=0;i<model.bones().size();i++)if(model.bones().get(i).name().equals(name))return i;throw new AssertionError("Missing bone: "+name);}
    private static Geometry.Face face(AvatarDocument model,int bone,String name){return model.bones().get(bone).meshes().get(0).faces().stream().filter(f->f.material().equals(name)).findFirst().orElseThrow();}
    private static void faceUv(AvatarDocument model,int bone,String side,float[] rectangle){
        var v=face(model,bone,side).vertices();
        close(rectangle[0]/64,v.get(0).u(),side+" top-left U");close(rectangle[1]/64,v.get(0).v(),side+" top-left V");
        close(rectangle[2]/64,v.get(2).u(),side+" bottom-right U");close(rectangle[3]/64,v.get(2).v(),side+" bottom-right V");
    }
    private static void bounds(AvatarDocument model,int bone,float[] min,float[] max){
        float[] actualMin={Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY},actualMax={Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY};
        var world=model.restWorld().get(bone);
        for(var mesh:model.bones().get(bone).meshes().values())for(var face:mesh.faces())for(var vertex:face.vertices()){
            Vector3f position=world.transformPosition(vertex.position());
            for(int axis=0;axis<3;axis++){actualMin[axis]=Math.min(actualMin[axis],position.get(axis));actualMax[axis]=Math.max(actualMax[axis],position.get(axis));}
        }
        for(int i=0;i<3;i++){close(min[i],actualMin[i],"bounds minimum "+i);close(max[i],actualMax[i],"bounds maximum "+i);}
    }
    private static void reject(String json,String id,String fragment)throws Exception{
        boolean rejected=false;
        try{parse(json,id);}catch(IllegalArgumentException error){rejected=true;check(error.getMessage().contains(fragment),"diagnostic contains "+fragment+": "+error.getMessage());}
        check(rejected,"invalid model rejected: "+fragment);
    }
    private static void close(float expected,float actual,String label){check(Math.abs(expected-actual)<1e-5,label+" expected="+expected+" actual="+actual);}
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
}
