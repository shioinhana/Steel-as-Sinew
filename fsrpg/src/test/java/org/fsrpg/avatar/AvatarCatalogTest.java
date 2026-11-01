package org.fsrpg.avatar;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;

public final class AvatarCatalogTest {
    private static int checks;
    private static Path temporary;
    private static byte[] png;
    public static void main(String[] args)throws Exception {
        temporary=Files.createTempDirectory("fsrpg-avatar-catalog-").toAbsolutePath().normalize();
        png=AvatarSkinTest.png(new BufferedImage(64,64,BufferedImage.TYPE_INT_ARGB));
        try {
            var empty=AvatarCatalog.load(temporary.resolve("new"));
            check(empty.profiles().isEmpty()&&Files.isDirectory(temporary.resolve("new")),"creates missing directory");
            UUID id=UUID.fromString("12345678-1234-5678-9abc-123456789abc"),other=UUID.randomUUID();
            Path selection=directory("selection");
            Files.write(selection.resolve("player.png"),png);Files.write(selection.resolve("default.slim.png"),png);Files.write(selection.resolve(id+".png"),png);
            var snapshot=AvatarCatalog.load(selection);
            check(snapshot.profiles().size()==3,"three named assignments");
            check(snapshot.select(id,true).key().equals(id.toString()),"UUID overrides local player");
            check(snapshot.select(other,true).key().equals("player"),"local player overrides default");
            check(snapshot.select(other,false).key().equals("default"),"remote player uses default");
            check(snapshot.select(other,false).slim(),"filename selects slim model");
            check(Arrays.equals(Files.readAllBytes(selection.resolve("player.png")),png),"source PNG unchanged");
            check(snapshot.select(id,true).document()==null&&snapshot.select(id,true).skinPng()!=null,"Java profile contains normalized PNG");
            Files.writeString(selection.resolve("notes.txt"),"local note");
            check(AvatarCatalog.load(selection).diagnostics().size()==1,"unassigned files diagnosed");

            Path skin=directory("folder").resolve("player");Files.createDirectories(skin.resolve("textures"));
            Files.write(skin.resolve("textures/custom.png"),png);
            Files.writeString(skin.resolve("avatar.json"),"{\"type\":\"skin\",\"texture\":\"textures/custom.png\",\"slim\":true}");
            check(AvatarCatalog.load(skin.getParent()).select(other,true).slim(),"folder explicit slim texture");
            Files.write(skin.getParent().resolve("player.png"),png);
            rejects(skin.getParent(),"folder and direct assignment ambiguity");

            Path both=directory("two-png");Files.write(both.resolve("player.png"),png);Files.write(both.resolve("player.slim.png"),png);
            rejects(both,"two skin types ambiguous");
            Path models=directory("two-models");Files.writeString(models.resolve("player.bbmodel"),"{}");Files.writeString(models.resolve("player.geo.json"),"{}");
            rejects(models,"two model formats ambiguous");
            folderFailure("escape","{\"type\":\"skin\",\"texture\":\"../outside.png\"}","path traversal");
            folderFailure("absolute","{\"type\":\"skin\",\"texture\":\"C:/outside.png\"}","absolute path");
            folderFailure("unknown","{\"type\":\"skin\",\"surprise\":true}","unknown config option");
            folderFailure("bool","{\"type\":\"skin\",\"slim\":\"true\"}","slim must be Boolean");
            folderFailure("type","{\"type\":\"unknown\"}","unknown format");
            folderFailure("skin-with-model","{\"type\":\"skin\",\"model\":\"geometry.json\"}","no silently ignored skin model");

            Path pack=directory("pack").resolve("player");Files.createDirectory(pack);Files.write(pack.resolve("first.png"),png);Files.write(pack.resolve("second.png"),png);
            Files.writeString(pack.resolve("skins.json"),"{\"skins\":[{\"localization_name\":\"classic\",\"geometry\":\"geometry.humanoid.custom\",\"texture\":\"first.png\"},{\"localization_name\":\"alex\",\"geometry\":\"geometry.humanoid.customSlim\",\"texture\":\"second.png\"}]}");
            rejects(pack.getParent(),"multiple pack skins require choice");
            Files.writeString(pack.resolve("avatar.json"),"{\"skin\":\"alex\"}");
            var chosen=AvatarCatalog.load(pack.getParent()).select(other,true);
            check(chosen.slim()&&chosen.source().getFileName().toString().equals("second.png"),"explicit skin pack selection and slim geometry");
            check(chosen.document()==null,"built-in Bedrock humanoid uses official Java model");
            Files.writeString(pack.resolve("avatar.json"),"{\"skin\":\"missing\"}");rejects(pack.getParent(),"missing skin selection rejected");
            Files.writeString(pack.resolve("avatar.json"),"{\"skin\":\"alex\",\"geometry\":\"geometry.custom.hat\"}");rejects(pack.getParent(),"custom geometry cannot silently become a flat skin");
            check(snapshot.select(id,true).key().equals(id.toString()),"old snapshot remains usable after another load fails");
            System.out.println("AvatarCatalogTest passed: "+checks+" checks");
        } finally {
            if(!temporary.getFileName().toString().startsWith("fsrpg-avatar-catalog-"))throw new AssertionError("Unsafe temp cleanup target");
            try(var paths=Files.walk(temporary)){for(Path path:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.delete(path);}
        }
    }
    private static Path directory(String name)throws IOException{Path path=temporary.resolve(name);Files.createDirectory(path);return path;}
    private static void folderFailure(String name,String config,String message)throws IOException {
        Path root=directory(name),folder=root.resolve("player");Files.createDirectory(folder);Files.write(folder.resolve("skin.png"),png);Files.writeString(folder.resolve("avatar.json"),config);rejects(root,message);
    }
    private static void rejects(Path root,String message)throws IOException {
        try{AvatarCatalog.load(root);throw new AssertionError(message);}catch(IOException | IllegalArgumentException expected){check(true,message);}
    }
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
}
