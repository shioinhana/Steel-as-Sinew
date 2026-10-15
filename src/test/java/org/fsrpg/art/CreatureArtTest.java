package org.fsrpg.art;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

public final class CreatureArtTest {
    private static void require(boolean value,String text){if(!value)throw new AssertionError(text);}
    public static void main(String[] args)throws Exception{
        Path root=Path.of("src/main/resources"),sources=Path.of("artwork/imports/creatures");int models=0,clips=0,samples=0,faces=0;
        for(var value:ArtCatalog.DATA.getAsJsonArray("creatures")){
            var row=value.getAsJsonObject();String id=row.get("id").getAsString();
            var data=ArtCatalog.read("/assets/fsrpg/art/creatures/"+id+".json");var model=new CreatureArt(data);
            Path source;try(var paths=Files.walk(sources)){source=paths.filter(p->p.getFileName().toString().equals(data.get("source").getAsString())).findFirst().orElseThrow();}
            var original=JsonParser.parseString(Files.readString(source)).getAsJsonObject();
            require(model.clips.size()==original.getAsJsonArray("animations").size(),id+" source clips lost");
            for(var texture:data.getAsJsonArray("textures")){
                var t=texture.getAsJsonObject();Path file=root.resolve("assets/fsrpg/"+t.get("id").getAsString().split(":",2)[1]);
                require(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))).equals(t.get("sha256").getAsString()),id+" texture bytes changed");
            }
            for(int i=0;i<model.nodes.size();i++){
                var n=model.nodes.get(i);require(n.parent()<i,id+" invalid hierarchy");
                for(var mesh:n.meshes().values())for(var face:mesh.faces()){require(face.vertices().size()==4,id+" invalid quad");faces++;}
            }
            for(var clip:model.clips.values()){
                for(double time=0;time<=clip.length()+1.0/60;time+=1.0/60){
                    var sample=model.sample(clip.id(),time,false);require(sample.matrices().size()==model.nodes.size(),id+" missing bones");
                    for(var matrix:sample.matrices())for(float number:matrix.get(new float[16]))require(Float.isFinite(number),id+" nonfinite "+clip.id()+" @ "+time);samples++;
                }
                for(var track:clip.tracks())for(var frame:track.frames()){
                    float[] actual=track.value(frame.time());
                    // Duplicate source timestamps deliberately take the last post value.
                    var last=track.frames().stream().filter(f->f.time()==frame.time()).reduce((a,b)->b).orElseThrow();
                    require(java.util.Arrays.equals(actual,last.post()),id+" keyframe changed");
                }
                clips++;
            }models++;
        }
        var a=new CreatureArt.Frame(0,new float[]{0,0,0},new float[]{0,0,0},"linear");
        var b=new CreatureArt.Frame(1,new float[]{10,20,30},new float[]{10,20,30},"linear");
        require(new CreatureArt.Track(0,"position",List.of(a,b)).value(.5)[1]==10,"linear midpoint");
        require(models==30&&clips==117,"source inventory changed unexpectedly");
        System.out.println("CreatureArtTest passed: "+models+" models / "+clips+" clips / "+samples+" 60Hz FK samples / "+faces+" quads; source textures byte-identical");
    }
}
