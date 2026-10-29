package org.fsrpg.avatar;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import javax.imageio.ImageIO;
import org.joml.Vector3f;

/** Import invariants, numeric geometry and rejection of lossy/unsafe source assets. */
public final class BlockbenchAvatarTest {
    private static int checks;
    private static void require(boolean condition,String message) {checks++;if(!condition)throw new AssertionError(message);}
    private static void near(float actual,float expected,String message) {require(Math.abs(actual-expected)<1e-6f,message+": "+actual+" != "+expected);}
    private static JsonObject object(String source) {return JsonParser.parseString(source).getAsJsonObject();}
    private static byte[] png(int width,int height) throws Exception {
        var image=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0,0,0xff127abc);var bytes=new ByteArrayOutputStream();ImageIO.write(image,"PNG",bytes);return bytes.toByteArray();
    }
    private static JsonObject fixture(byte[] first,byte[] second) {
        JsonObject model=object("""
            {"meta":{"format_version":"4.10","model_format":"free"},
             "textures":[],"elements":[{"uuid":"cube","type":"cube","from":[0,8,0],"to":[8,16,8],
               "origin":[0,8,0],"rotation":[0,90,0],"inflate":1,
               "faces":{"north":{"texture":"texture-b","uv":[0,0,4,4],"rotation":90}}}],
             "outliner":[{"uuid":"head-id","name":"head","origin":[0,16,0],"rotation":[0,0,90],
               "children":[{"uuid":"arm-id","name":"right_arm","origin":[0,8,0],"children":["cube"]}]}]}
            """);
        for(int i=0;i<2;i++) {
            var texture=new JsonObject();texture.addProperty("name","texture-"+i+".png");
            texture.addProperty("uuid",i==0?"texture-a":"texture-b");
            texture.addProperty("source","data:image/png;base64,"+Base64.getEncoder().encodeToString(i==0?first:second));
            texture.addProperty("uv_width",8);texture.addProperty("uv_height",8);
            model.getAsJsonArray("textures").add(texture);
        }
        return model;
    }
    private static AvatarDocument parse(Path folder,JsonObject data) throws Exception {
        Path model=folder.resolve("avatar.bbmodel");Files.writeString(model,data.toString());return BlockbenchAvatar.parse(model);
    }
    @FunctionalInterface private interface Checked {void run()throws Exception;}
    private static void rejects(Checked operation,String message) throws Exception {
        try {operation.run();throw new AssertionError("Accepted "+message);}
        catch(IllegalArgumentException|java.io.IOException expected) {checks++;}
    }
    private static JsonObject cube(JsonObject model) {return model.getAsJsonArray("elements").get(0).getAsJsonObject();}
    private static JsonObject firstGroup(JsonObject model) {return model.getAsJsonArray("outliner").get(0).getAsJsonObject();}
    public static void main(String[] args) throws Exception {
        Files.createDirectories(Path.of("build"));Path folder=Files.createTempDirectory(Path.of("build"),"blockbench-avatar-test-");
        try {
            byte[] a=png(8,8),b=png(16,16);JsonObject source=fixture(a,b);
            var document=parse(folder,source);
            require(document.bones().size()==2,"BB4 inline bones preserved");
            require(document.bones().get(1).parent()==0,"parent index");
            require(document.bones().get(1).name().equals("right_arm"),"source bone name");
            require(Arrays.equals(a,document.textures().get(0).png())&&Arrays.equals(b,document.textures().get(1).png()),"original PNG bytes preserved");
            var face=document.bones().get(1).meshes().get(1).faces().getFirst();var vertex=face.vertices().getFirst();
            near(vertex.x(),-1f/16,"cube local rotation X");near(vertex.y(),9f/16,"inflate and pivot Y");near(vertex.z(),-9f/16,"independent cube rotation Z");
            near(vertex.u(),0,"UV quarter turn U");near(vertex.v(),.5f,"UV uses declared UV height");
            Vector3f world=document.restWorld().get(1).transformPosition(new Vector3f(vertex.x(),vertex.y(),vertex.z()));
            near(world.x(),-1f/16,"group rotated world X");near(world.y(),15f/16,"nested group world Y");near(world.z(),-9f/16,"nested group world Z");
            near(vertex.nx(),-1,"independent face normal X");near(vertex.ny(),0,"independent face normal Y");near(vertex.nz(),0,"independent face normal Z");

            JsonObject five=source.deepCopy();five.getAsJsonObject("meta").addProperty("format_version","5.0");
            var groups=new JsonArray();JsonObject head=firstGroup(five),arm=head.getAsJsonArray("children").get(0).getAsJsonObject();
            JsonObject headDefinition=head.deepCopy(),armDefinition=arm.deepCopy();headDefinition.remove("children");armDefinition.remove("children");
            groups.add(headDefinition);groups.add(armDefinition);five.add("groups",groups);
            head.remove("name");head.remove("origin");head.remove("rotation");arm.remove("name");arm.remove("origin");
            require(parse(folder,five).bones().get(1).local().equals(document.bones().get(1).local()),"BB5 split group definitions preserve pivots");
            JsonObject relative=source.deepCopy();Files.write(folder.resolve("relative.png"),a);
            var texture=relative.getAsJsonArray("textures").get(0).getAsJsonObject();texture.remove("source");texture.addProperty("relative_path","relative.png");
            require(Arrays.equals(parse(folder,relative).textures().get(0).png(),a),"relative PNG file");
            texture.addProperty("relative_path","../outside.png");rejects(()->parse(folder,relative),"texture traversal");
            texture.addProperty("relative_path","C:/outside.png");rejects(()->parse(folder,relative),"absolute texture path");
            texture.addProperty("relative_path","relative.png");Files.writeString(folder.resolve("relative.png"),"not a PNG");rejects(()->parse(folder,relative),"invalid PNG");

            JsonObject invalid=source.deepCopy();cube(invalid).addProperty("type","mesh");JsonObject test=invalid;
            rejects(()->parse(folder,test),"unsupported mesh");
            invalid=source.deepCopy();cube(invalid).addProperty("rescale",true);JsonObject rescale=invalid;rejects(()->parse(folder,rescale),"unsupported rescale");
            invalid=source.deepCopy();cube(invalid).add("from",JsonParser.parseString("[1e999,0,0]"));JsonObject infinity=invalid;rejects(()->parse(folder,infinity),"nonfinite coordinate");
            invalid=source.deepCopy();cube(invalid).add("to",JsonParser.parseString("[-1,16,8]"));JsonObject inverted=invalid;rejects(()->parse(folder,inverted),"inverted cube");
            invalid=source.deepCopy();cube(invalid).getAsJsonObject("faces").getAsJsonObject("north").addProperty("texture",100);JsonObject missingTexture=invalid;rejects(()->parse(folder,missingTexture),"missing texture");
            invalid=source.deepCopy();cube(invalid).getAsJsonObject("faces").getAsJsonObject("north").addProperty("rotation",45);JsonObject uvRotation=invalid;rejects(()->parse(folder,uvRotation),"non-quarter UV rotation");
            invalid=source.deepCopy();invalid.getAsJsonArray("elements").add(cube(invalid).deepCopy());JsonObject duplicate=invalid;rejects(()->parse(folder,duplicate),"duplicate cube UUID");
            invalid=source.deepCopy();firstGroup(invalid).getAsJsonArray("children").add("head-id");JsonObject cycle=invalid;rejects(()->parse(folder,cycle),"cyclic outliner");
            invalid=source.deepCopy();firstGroup(invalid).getAsJsonArray("children").add("missing");JsonObject missing=invalid;rejects(()->parse(folder,missing),"missing bone");
            invalid=source.deepCopy();firstGroup(invalid).getAsJsonArray("children").get(0).getAsJsonObject().getAsJsonArray("children").add("cube");JsonObject duplicateReference=invalid;rejects(()->parse(folder,duplicateReference),"duplicate cube reference");
            invalid=source.deepCopy();firstGroup(invalid).getAsJsonArray("children").get(0).getAsJsonObject().addProperty("name","head");JsonObject duplicateName=invalid;rejects(()->parse(folder,duplicateName),"duplicate bone name");
            invalid=source.deepCopy();firstGroup(invalid).addProperty("visibility",false);JsonObject hidden=invalid;rejects(()->parse(folder,hidden),"invisible model with no rendered faces");
            invalid=source.deepCopy();invalid.getAsJsonArray("textures").get(0).getAsJsonObject().addProperty("source","data:image/png;base64,???");JsonObject corrupt=invalid;rejects(()->parse(folder,corrupt),"corrupt embedded texture");
            byte[] giant=a.clone();giant[16]=0;giant[17]=0;giant[18]=0x20;giant[19]=0;rejects(()->AvatarGeometry.pngSize(giant),"oversized PNG dimensions before decoding");
            Path oversized=folder.resolve("oversized.json");Files.writeString(oversized,"{}".repeat(100));rejects(()->AvatarGeometry.readJson(oversized,64),"file size limit");
            int original=0;
            try(var files=Files.walk(Path.of("artwork/imports/creatures"))) {
                for(Path path:files.filter(p->p.toString().endsWith(".bbmodel")).toList()) {
                    var model=BlockbenchAvatar.parse(path);require(!model.bones().isEmpty(),"existing source bones: "+path.getFileName());original++;
                }
            }
            require(original==30,"all existing cube creature sources parse");
            System.out.println("BlockbenchAvatarTest passed: "+checks+" checks; BB4/5 hierarchy, rotation, inflate, UV, PNG, bounds and "+original+" source models");
        } finally {
            try(var paths=Files.walk(folder)) {for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}
        }
    }
}
