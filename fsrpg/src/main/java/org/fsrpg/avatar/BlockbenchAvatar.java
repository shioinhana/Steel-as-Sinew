package org.fsrpg.avatar;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.fsvoxel.render.Geometry;
import org.joml.Matrix4f;

/** Blockbench 4/5 static cube appearance. Authored animations are deliberately separate. */
public final class BlockbenchAvatar {
    private static final int MAX_ELEMENTS=8192,MAX_BONES=1024,MAX_DEPTH=64;
    private final Path model;
    private final JsonObject data;
    private final Map<String,JsonObject> elements=new LinkedHashMap<>(),groups=new LinkedHashMap<>();
    private final Map<String,Integer> textureIds=new LinkedHashMap<>();
    private final List<AvatarDocument.Texture> textures=new ArrayList<>();
    private final List<int[]> textureSizes=new ArrayList<>();
    private final List<MutableBone> bones=new ArrayList<>();
    private final Set<String> visited=new HashSet<>(),names=new HashSet<>();
    private int root=-1;
    private record MutableBone(String name,int parent,Matrix4f local,float[] origin,Map<Integer,List<Geometry.Face>> faces) {}
    private BlockbenchAvatar(Path model,JsonObject data) {this.model=model;this.data=data;}
    public static AvatarDocument parse(Path model) throws IOException {
        return new BlockbenchAvatar(model,AvatarGeometry.readJson(model,AvatarGeometry.MAX_JSON_BYTES)).parse();
    }
    private AvatarDocument parse() throws IOException {
        JsonObject meta=object(data.get("meta"),"meta");
        String version=string(meta,"format_version");
        if(!version.matches("[45]\\.[0-9]+(?:\\.[0-9]+)?"))throw invalid("Only Blockbench format versions 4.x and 5.x are supported");
        loadTextures();
        JsonArray sourceElements=array(data.get("elements"),"elements");
        if(sourceElements.size()>MAX_ELEMENTS)throw invalid("Avatar exceeds 8192 cubes");
        for(JsonElement value:sourceElements) {
            JsonObject element=object(value,"element");String id=string(element,"uuid");
            if(elements.putIfAbsent(id,element)!=null)throw invalid("Duplicate element UUID: "+id);
            String type=element.has("type")?string(element,"type"):"cube";
            if(!type.equals("cube"))throw invalid("Unsupported Blockbench element type '"+type+"'; export a cube model");
            if(flag(element,"rescale",false))throw invalid("Blockbench cube rescale is not supported; apply the transform before importing");
        }
        if(data.has("groups"))for(JsonElement value:array(data.get("groups"),"groups")) {
            JsonObject group=object(value,"group");String id=string(group,"uuid");
            if(elements.containsKey(id)||groups.putIfAbsent(id,group)!=null)throw invalid("Duplicate group UUID: "+id);
        }
        JsonArray outliner=array(data.get("outliner"),"outliner");
        if(outliner.isEmpty())throw invalid("Avatar has no outliner; named human bones are required");
        for(JsonElement value:outliner) visit(value,-1,new float[3],true,0);
        if(!visited.containsAll(elements.keySet()))throw invalid("Blockbench outliner is missing cube references");
        if(!visited.containsAll(groups.keySet()))throw invalid("Blockbench outliner is missing bone references");
        List<AvatarDocument.Bone> result=new ArrayList<>();
        int faces=0;
        for(var bone:bones) {
            var meshes=AvatarGeometry.meshes(bone.faces());
            for(var mesh:meshes.values())faces+=mesh.faces().size();
            result.add(new AvatarDocument.Bone(bone.name(),bone.parent(),bone.local(),meshes));
        }
        if(faces==0)throw invalid("Avatar has no visible textured cube faces");
        return new AvatarDocument(result,textures);
    }

    private void loadTextures() throws IOException {
        JsonArray source=array(data.get("textures"),"textures");
        if(source.isEmpty()||source.size()>32)throw invalid("Avatar must contain 1..32 textures");
        long total=0;
        for(int i=0;i<source.size();i++) {
            JsonObject texture=object(source.get(i),"texture");String name=string(texture,"name");byte[] png;
            String embedded=texture.has("source")&&!texture.get("source").isJsonNull()?texture.get("source").getAsString():"";
            if(embedded.startsWith("data:"))png=AvatarGeometry.embeddedPng(embedded);
            else {
                String relative=texture.has("relative_path")&&!texture.get("relative_path").getAsString().isBlank()?texture.get("relative_path").getAsString()
                    :!embedded.isBlank()?embedded:texture.has("path")?texture.get("path").getAsString():name;
                png=AvatarGeometry.readPng(AvatarGeometry.relativeFile(model,relative));
            }
            total+=png.length;if(total>64L*1024*1024)throw invalid("Avatar textures exceed 64 MiB");
            int[] actual=AvatarGeometry.pngSize(png);
            int width=dimension(texture,"uv_width",dimension(texture,"width",actual[0]));
            int height=dimension(texture,"uv_height",dimension(texture,"height",actual[1]));
            textures.add(new AvatarDocument.Texture(name,png));textureSizes.add(new int[]{width,height});
            putTextureId(Integer.toString(i),i);
            if(texture.has("uuid"))putTextureId(string(texture,"uuid"),i);
            if(texture.has("id"))putTextureId(texture.get("id").getAsString(),i);
        }
    }
    private void putTextureId(String id,int index) {
        Integer previous=textureIds.putIfAbsent(id,index);
        if(previous!=null&&previous!=index)throw invalid("Duplicate texture identifier: "+id);
    }
    private int dimension(JsonObject object,String key,int fallback) {
        if(!object.has(key))return fallback;
        float value=AvatarGeometry.number(object.get(key),"texture."+key);
        if(value!=Math.rint(value)||value<1||value>4096)throw invalid("Avatar UV dimensions must be integers in 1..4096");
        return (int)value;
    }

    private void visit(JsonElement value,int parent,float[] parentOrigin,boolean enabled,int depth) {
        if(depth>MAX_DEPTH)throw invalid("Avatar bone hierarchy exceeds 64 levels or contains a cycle");
        String id;JsonObject outline=null;
        if(value.isJsonPrimitive()&&value.getAsJsonPrimitive().isString())id=value.getAsString();
        else {outline=object(value,"outliner entry");id=string(outline,"uuid");}
        if(!visited.add(id))throw invalid("Duplicate or cyclic Blockbench outliner reference: "+id);
        JsonObject element=elements.get(id);
        if(element!=null) {
            if(outline!=null&&outline.has("children")&&!array(outline.get("children"),"cube children").isEmpty())throw invalid("Cube cannot contain bone children");
            boolean draw=enabled&&flag(element,"export",true)&&flag(element,"visibility",true);
            addElement(element,parent,parentOrigin,draw);return;
        }
        JsonObject group=groups.get(id);
        if(group==null) {
            if(outline==null||!outline.has("name"))throw invalid("Missing Blockbench bone or cube UUID: "+id);
            group=outline; // Blockbench 4 stores group definitions inside the outliner.
        }
        String name=string(group,"name");
        if(!names.add(name))throw invalid("Duplicate avatar bone name: "+name);
        float[] origin=vec(group,"origin",new float[3]),rotation=vec(group,"rotation",new float[3]);
        Matrix4f local=new Matrix4f().translation((origin[0]-parentOrigin[0])/16,(origin[1]-parentOrigin[1])/16,(origin[2]-parentOrigin[2])/16)
            .rotateZYX(AvatarGeometry.radians(rotation[2]),AvatarGeometry.radians(rotation[1]),AvatarGeometry.radians(rotation[0]));
        if(bones.size()>=MAX_BONES)throw invalid("Avatar exceeds 1024 bones");
        int index=bones.size();bones.add(new MutableBone(name,parent,local,origin,new LinkedHashMap<>()));
        enabled=enabled&&flag(group,"export",true)&&flag(group,"visibility",true);
        JsonElement children=outline!=null&&outline.has("children")?outline.get("children"):group.get("children");
        if(children!=null)for(JsonElement child:array(children,"bone children"))visit(child,index,origin,enabled,depth+1);
    }

    private void addElement(JsonObject element,int parent,float[] parentOrigin,boolean draw) {
        float[] from=vec(element,"from",null),to=vec(element,"to",null),origin=vec(element,"origin",new float[3]);
        float[] rotation=vec(element,"rotation",new float[3]);
        float inflate=element.has("inflate")?AvatarGeometry.number(element.get("inflate"),"cube.inflate"):0;
        for(int i=0;i<3;i++)if(to[i]<from[i]||to[i]+inflate<from[i]-inflate)throw invalid("Inverted Blockbench cube bounds");
        JsonObject sourceFaces=object(element.get("faces"),"cube.faces");
        var faces=new LinkedHashMap<String,AvatarGeometry.FaceUv>();
        for(var entry:sourceFaces.entrySet()) {
            if(!AvatarGeometry.SIDES.contains(entry.getKey()))throw invalid("Unknown avatar cube face: "+entry.getKey());
            JsonObject face=object(entry.getValue(),"cube face");JsonElement ref=face.get("texture");
            if(ref==null||ref.isJsonNull()||(ref.isJsonPrimitive()&&ref.getAsString().equals("-1")))continue;
            if(!ref.isJsonPrimitive())throw invalid("Invalid cube texture reference");
            Integer texture=textureIds.get(ref.getAsString());
            if(texture==null)throw invalid("Missing cube texture: "+ref.getAsString());
            float[] uv=AvatarGeometry.vector(face.get("uv"),4,"cube UV");
            float rotationValue=face.has("rotation")?AvatarGeometry.number(face.get("rotation"),"face.rotation"):0;
            if(rotationValue%90!=0)throw invalid("Cube UV rotation must be a multiple of 90 degrees");
            int[] size=textureSizes.get(texture);
            faces.put(entry.getKey(),new AvatarGeometry.FaceUv(texture,uv,(int)rotationValue,size[0],size[1]));
        }
        if(!draw)return;
        if(parent<0) {
            if(root<0) {
                String name="__blockbench_root";
                if(!names.add(name))throw invalid("Reserved avatar bone name: "+name);
                root=bones.size();bones.add(new MutableBone(name,-1,new Matrix4f(),new float[3],new LinkedHashMap<>()));
            }
            parent=root;parentOrigin=new float[3];
        }
        AvatarGeometry.addCube(bones.get(parent).faces(),from,to,origin,rotation,inflate,faces,parentOrigin);
    }
    private static float[] vec(JsonObject object,String key,float[] fallback) {
        if(!object.has(key)) {if(fallback==null)throw invalid("Missing cube."+key);return fallback;}
        return AvatarGeometry.vector(object.get(key),3,key);
    }
    private static JsonObject object(JsonElement value,String label) {
        if(value==null||!value.isJsonObject())throw invalid(label+" must be an object");return value.getAsJsonObject();
    }
    private static JsonArray array(JsonElement value,String label) {
        if(value==null||!value.isJsonArray())throw invalid(label+" must be an array");return value.getAsJsonArray();
    }
    private static String string(JsonObject object,String key) {
        JsonElement value=object.get(key);
        if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString()||value.getAsString().isBlank()||value.getAsString().length()>256)
            throw invalid("Missing or invalid avatar "+key);
        return value.getAsString();
    }
    private static boolean flag(JsonObject object,String key,boolean fallback) {
        if(!object.has(key))return fallback;JsonElement value=object.get(key);
        if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isBoolean())throw invalid(key+" must be boolean");return value.getAsBoolean();
    }
    private static IllegalArgumentException invalid(String message) {return AvatarGeometry.invalid(message);}
}
