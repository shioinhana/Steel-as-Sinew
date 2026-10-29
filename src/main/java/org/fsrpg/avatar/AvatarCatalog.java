package org.fsrpg.avatar;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Discovers explicit, local appearance assignments; never downloads or rewrites user assets. */
public final class AvatarCatalog {
    public record Profile(String key, AvatarDocument document, byte[] skinPng, boolean slim, Path source) {
        public Profile {
            if((document==null)==(skinPng==null))throw new IllegalArgumentException("Profile must contain a model or a Java skin");
        }
    }
    public record Snapshot(Map<String,Profile> profiles,List<String> diagnostics) {
        public Snapshot { profiles=Map.copyOf(profiles); diagnostics=List.copyOf(diagnostics); }
        public Profile select(UUID player,boolean local) {
            Profile selected=profiles.get(player.toString().toLowerCase(Locale.ROOT));
            if(selected==null && local)selected=profiles.get("player");
            return selected==null?profiles.get("default"):selected;
        }
    }
    private static final Set<String> OPTIONS=Set.of("type","model","texture","slim","geometry","skin");
    private static final Set<String> STANDARD_BEDROCK=Set.of("geometry.humanoid","geometry.humanoid.custom","geometry.humanoid.customSlim");
    private AvatarCatalog() {}

    /** Fully validates the new collection before the caller replaces its last usable snapshot. */
    public static Snapshot load(Path directory) throws IOException {
        Files.createDirectories(directory);
        Path root=directory.toRealPath();
        var keys=new LinkedHashSet<String>();var diagnostics=new ArrayList<String>();
        try(var stream=Files.list(root)) {
            for(Path path:stream.sorted().toList()) {
                String filename=path.getFileName().toString();
                String key=selector(filename,Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS));
                if(key!=null)keys.add(key);
                else if(!filename.startsWith(".")&&!filename.equalsIgnoreCase("README.md"))diagnostics.add("Ignored unassigned avatar entry: "+filename);
                if(keys.size()>256)throw new IOException("At most 256 player avatar assignments are supported");
            }
        }
        var profiles=new LinkedHashMap<String,Profile>();
        for(String key:keys) {
            Path folder=existing(root,key);
            boolean isFolder=folder!=null && Files.isDirectory(folder);
            var direct=new ArrayList<Path>();
            for(String suffix:List.of(".bbmodel",".png",".slim.png",".geometry.json",".geo.json")) {
                Path found=existing(root,key+suffix);if(found!=null)direct.add(found);
            }
            if(isFolder && !direct.isEmpty())throw new IOException("Use a folder or direct files, not both, for avatar "+key);
            Profile profile=isFolder?folder(root,key,folder):direct(root,key,direct);
            profiles.put(key,profile);
        }
        return new Snapshot(profiles,diagnostics);
    }

    private static Profile direct(Path root,String key,List<Path> files) throws IOException {
        Path model=null,texture=null;boolean slim=false;
        for(Path file:files) {
            file=inside(root,file);
            if(file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png")) {
                if(texture!=null)throw new IOException("Ambiguous classic/slim PNG for avatar "+key);
                texture=file;slim=file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".slim.png");
            } else {
                if(model!=null)throw new IOException("Multiple model files for avatar "+key);
                model=file;
            }
        }
        if(model!=null && model.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".bbmodel"))
            return new Profile(key,BlockbenchAvatar.parse(model),null,slim,model);
        if(model!=null) {
            if(texture==null)throw new IOException("Bedrock avatar "+key+" requires "+key+".png");
            return new Profile(key,BedrockAvatar.parse(model,texture,""),null,slim,model);
        }
        if(texture==null)throw new IOException("No supported avatar asset for "+key);
        return new Profile(key,null,AvatarSkin.load(texture),slim,texture);
    }

    private static Profile folder(Path root,String key,Path path) throws IOException {
        Path folder=inside(root,path);
        Path configFile=existing(folder,"avatar.json");
        JsonObject config=configFile==null?new JsonObject():json(inside(folder,configFile));
        for(String option:config.keySet())if(!OPTIONS.contains(option))throw new IOException("Unknown avatar.json option: "+option);
        String type=text(config,"type",""),modelName=text(config,"model",""),textureName=text(config,"texture",""),geometry=text(config,"geometry","");
        String skin=text(config,"skin","");
        boolean slim=bool(config,"slim",false);
        Path bb=existing(folder,"model.bbmodel"),geo=existing(folder,"geometry.json"),pack=existing(folder,"skins.json");
        if(type.isEmpty()) {
            if(!modelName.isEmpty())type=modelName.toLowerCase(Locale.ROOT).endsWith(".bbmodel")?"blockbench":"bedrock";
            else {
                if(bb!=null && (geo!=null || pack!=null))throw new IOException("Ambiguous BB/Bedrock files in "+folder+"; set avatar.json type");
                type=bb!=null?"blockbench":geo!=null || pack!=null?"bedrock":"skin";
            }
        }
        if(type.equals("blockbench") || type.equals("bbmodel")) {
            if(!textureName.isEmpty() || !geometry.isEmpty() || !skin.isEmpty())
                throw new IOException("BB textures are read from the bbmodel; texture/geometry/skin options do not apply");
            Path model=required(folder,modelName.isEmpty()?"model.bbmodel":modelName);
            if(!model.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".bbmodel"))throw new IOException("Blockbench models must use .bbmodel");
            return new Profile(key,BlockbenchAvatar.parse(model),null,slim,model);
        }
        if(type.equals("skin")) {
            if(!modelName.isEmpty() || !geometry.isEmpty() || !skin.isEmpty())throw new IOException("Java skin profile cannot declare model/geometry/skin");
            Path texture=required(folder,textureName.isEmpty()?"skin.png":textureName);
            return new Profile(key,null,AvatarSkin.load(texture),slim,texture);
        }
        if(!type.equals("bedrock"))throw new IOException("Unknown avatar type: "+type);
        if(pack!=null) {
            JsonObject data=json(inside(folder,pack));
            if(!data.has("skins") || !data.get("skins").isJsonArray())throw new IOException("skins.json needs a skins array");
            var choices=data.getAsJsonArray("skins");
            if(choices.isEmpty() || choices.size()>256)throw new IOException("skins.json must have 1..256 skins");
            JsonObject selected=null;
            if(skin.isEmpty() && choices.size()!=1)throw new IOException("Multiple skins in "+folder+"; choose localization_name with avatar.json skin");
            for(JsonElement value:choices) {
                if(!value.isJsonObject())throw new IOException("Invalid skins.json skin entry");
                JsonObject choice=value.getAsJsonObject();
                if(skin.isEmpty() || text(choice,"localization_name","").equals(skin)) {
                    if(selected!=null)throw new IOException("Duplicate skin localization_name: "+skin);
                    selected=choice;
                }
            }
            if(selected==null)throw new IOException("No skin named "+skin+" in skins.json");
            if(textureName.isEmpty())textureName=text(selected,"texture","");
            if(geometry.isEmpty())geometry=text(selected,"geometry","");
            if(!config.has("slim"))slim=geometry.equals("geometry.humanoid.customSlim");
        } else if(!skin.isEmpty())throw new IOException("avatar.json skin requires skins.json");
        Path texture=required(folder,textureName.isEmpty()?"skin.png":textureName);
        Path model=modelName.isEmpty()?geo:required(folder,modelName);
        if(model==null) {
            if(!STANDARD_BEDROCK.contains(geometry))throw new IOException("Custom Bedrock skin requires geometry.json or avatar.json model");
            return new Profile(key,null,AvatarSkin.load(texture),slim,texture);
        }
        return new Profile(key,BedrockAvatar.parse(inside(folder,model),texture,geometry),null,slim,model);
    }

    private static String selector(String filename,boolean folder) {
        String key=filename.toLowerCase(Locale.ROOT);
        if(!folder) {
            boolean matched=false;
            for(String suffix:List.of(".slim.png",".bbmodel",".geometry.json",".geo.json",".png"))if(key.endsWith(suffix)) {
                key=key.substring(0,key.length()-suffix.length());matched=true;break;
            }
            if(!matched)return null;
        }
        if(key.equals("player") || key.equals("default"))return key;
        try { return UUID.fromString(key).toString().equals(key)?key:null; }
        catch(IllegalArgumentException invalid){return null;}
    }
    private static Path existing(Path directory,String filename) throws IOException {
        // Case-insensitive naming on all platforms, and explicit duplicate detection.
        Path result=null;
        try(var entries=Files.list(directory)) {
            for(Path value:entries.toList())if(value.getFileName().toString().equalsIgnoreCase(filename)) {
                if(result!=null)throw new IOException("Case-ambiguous avatar filenames: "+filename);
                result=value;
            }
        }
        return result;
    }
    private static Path required(Path folder,String relative) throws IOException {
        if(relative.isBlank() || relative.contains(":"))throw new IOException("Avatar file must be a relative path: "+relative);
        Path child=folder.resolve(relative).normalize();
        if(!child.startsWith(folder) || Path.of(relative).isAbsolute())throw new IOException("Avatar path escapes its folder: "+relative);
        Path result=inside(folder,child);
        if(!Files.isRegularFile(result))throw new IOException("Missing avatar file: "+relative);
        return result;
    }
    private static Path inside(Path root,Path file) throws IOException {
        Path actual=file.toRealPath();
        if(!actual.startsWith(root.toRealPath()))throw new IOException("Avatar file escapes assigned folder: "+file);
        return actual;
    }
    private static JsonObject json(Path file) throws IOException {
        return AvatarGeometry.readJson(file,256*1024);
    }
    private static String text(JsonObject data,String key,String fallback) throws IOException {
        if(!data.has(key))return fallback;
        var value=data.get(key);
        if(!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())throw new IOException(key+" must be a string");
        String result=value.getAsString();if(result.length()>256)throw new IOException(key+" exceeds 256 characters");
        return result;
    }
    private static boolean bool(JsonObject data,String key,boolean fallback) throws IOException {
        if(!data.has(key))return fallback;
        var value=data.get(key);
        if(!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean())throw new IOException(key+" must be true or false");
        return value.getAsBoolean();
    }
}
