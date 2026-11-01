package org.fsrpg.art;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fsvoxel.render.Geometry;
import org.joml.Matrix4f;

/** Source Blockbench bones and numeric curves. No AI, damage or probability rules live here. */
public final class CreatureArt {
    public record Frame(float time,float[] pre,float[] post,String interpolation) {}
    public record Track(int node,String channel,List<Frame> frames) {
        public float[] value(double time) {
            if(time<frames.getFirst().time())return frames.getFirst().pre().clone();
            int left=0;while(left+1<frames.size()&&frames.get(left+1).time()<=time)left++;
            Frame a=frames.get(left);if(left==frames.size()-1||time==a.time())return a.post().clone();
            Frame b=frames.get(left+1);float t=(float)((time-a.time())/(b.time()-a.time()));
            float[] out=new float[3];
            if(a.interpolation().equals("step"))return a.post().clone();
            for(int i=0;i<3;i++) {
                float p1=a.post()[i],p2=b.pre()[i];
                if(a.interpolation().equals("catmullrom")||b.interpolation().equals("catmullrom")) {
                    float p0=frames.get(Math.max(0,left-1)).post()[i],p3=frames.get(Math.min(frames.size()-1,left+2)).pre()[i];
                    out[i]=.5f*((2*p1)+(-p0+p2)*t+(2*p0-5*p1+4*p2-p3)*t*t+(-p0+3*p1-3*p2+p3)*t*t*t);
                }else out[i]=p1+(p2-p1)*t;
            }
            return out;
        }
    }
    public record Clip(String id,String sourceName,double length,boolean loop,List<Track> tracks) {}
    public record Node(String name,int parent,float[] position,float[] rotation,Map<Integer,Geometry.Mesh> meshes) {}
    public record Sample(List<Matrix4f> matrices,boolean[] visible,String clip,double seconds) {}
    public final String id;
    public final List<String> textures;
    public final List<Node> nodes;
    public final Map<String,Clip> clips;
    public CreatureArt(JsonObject data) {
        id=data.get("id").getAsString();textures=new ArrayList<>();nodes=new ArrayList<>();clips=new LinkedHashMap<>();
        for(var v:data.getAsJsonArray("textures"))textures.add(v.getAsJsonObject().get("id").getAsString());
        for(var v:data.getAsJsonArray("nodes")) {
            var n=v.getAsJsonObject();var faces=new LinkedHashMap<Integer,List<Geometry.Face>>();
            for(var f:n.getAsJsonArray("faces")) {
                var face=f.getAsJsonObject();int texture=face.get("texture").getAsInt();var vertices=new ArrayList<Geometry.Vertex>();
                for(var vertex:face.getAsJsonArray("vertices")){float[] p=array(vertex.getAsJsonArray());vertices.add(new Geometry.Vertex(p[0],p[1],p[2],p[3],p[4],p[5],p[6],p[7]));}
                faces.computeIfAbsent(texture,k->new ArrayList<>()).add(new Geometry.Face(Integer.toString(texture),vertices));
            }
            var meshes=new LinkedHashMap<Integer,Geometry.Mesh>();faces.forEach((key,value)->meshes.put(key,new Geometry.Mesh(value)));
            nodes.add(new Node(n.get("name").getAsString(),n.get("parent").getAsInt(),array(n.getAsJsonArray("position")),array(n.getAsJsonArray("rotation")),meshes));
        }
        for(var v:data.getAsJsonArray("clips")) {
            var c=v.getAsJsonObject();var tracks=new ArrayList<Track>();
            for(var t:c.getAsJsonArray("tracks")) {
                var track=t.getAsJsonObject();var frames=new ArrayList<Frame>();
                for(var k:track.getAsJsonArray("frames")){var f=k.getAsJsonObject();frames.add(new Frame(f.get("time").getAsFloat(),array(f.getAsJsonArray("pre")),array(f.getAsJsonArray("post")),f.get("interpolation").getAsString()));}
                tracks.add(new Track(track.get("node").getAsInt(),track.get("channel").getAsString(),List.copyOf(frames)));
            }
            var clip=new Clip(c.get("id").getAsString(),c.get("sourceName").getAsString(),c.get("length").getAsDouble(),c.get("loop").getAsBoolean(),List.copyOf(tracks));clips.put(clip.id(),clip);
        }
    }
    public static float[] array(JsonArray values){float[] a=new float[values.size()];for(int i=0;i<a.length;i++)a[i]=values.get(i).getAsFloat();return a;}
    public String choose(String action,String variant) {
        if(clips.containsKey(action+"_"+variant))return action+"_"+variant;
        if(clips.containsKey(action))return action;
        return clips.keySet().stream().filter(k->k.startsWith(action+"_")).findFirst().orElse("");
    }
    public Sample sample(String name,double seconds,boolean loop) {
        Clip clip=clips.get(name);double time=clip==null?0:loop?((seconds%clip.length()+clip.length())%clip.length()):Math.clamp(seconds,0,clip.length());
        float[][] position=new float[nodes.size()][3],rotation=new float[nodes.size()][3],scale=new float[nodes.size()][3];
        for(var s:scale)java.util.Arrays.fill(s,1);
        if(clip!=null)for(var track:clip.tracks()) {
            float[] value=track.value(time);
            switch(track.channel()){case "rotation"->rotation[track.node()]=value;case "position"->position[track.node()]=value;case "scale"->scale[track.node()]=value;default->throw new IllegalArgumentException("Unknown source channel");}
        }
        var world=new ArrayList<Matrix4f>(nodes.size());boolean[] visible=new boolean[nodes.size()];
        for(int i=0;i<nodes.size();i++) {
            var n=nodes.get(i);var p=position[i];var r=rotation[i];var s=scale[i];
            var local=new Matrix4f().translation(n.position()[0]+p[0]/16,n.position()[1]+p[1]/16,n.position()[2]+p[2]/16)
                    .rotateZYX((float)Math.toRadians(n.rotation()[2]+r[2]),(float)Math.toRadians(n.rotation()[1]+r[1]),(float)Math.toRadians(n.rotation()[0]+r[0])).scale(s[0],s[1],s[2]);
            if(n.parent()>=0)local=new Matrix4f(world.get(n.parent())).mul(local);
            world.add(local);visible[i]=Math.abs(s[0]*s[1]*s[2])>1e-8&&(n.parent()<0||visible[n.parent()]);
        }
        return new Sample(List.copyOf(world),visible,name,time);
    }
}
