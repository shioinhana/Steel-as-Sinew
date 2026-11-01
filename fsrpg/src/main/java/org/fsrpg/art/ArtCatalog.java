package org.fsrpg.art;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Packaged source-art inventory, shared by registration and the client gallery. */
public final class ArtCatalog {
    public static final JsonObject DATA=read("/assets/fsrpg/art/catalog.json");
    private ArtCatalog() {}
    public static JsonObject read(String resource) {
        var stream=ArtCatalog.class.getResourceAsStream(resource);
        if(stream==null)throw new IllegalStateException("Missing art resource "+resource);
        try(var reader=new InputStreamReader(stream,StandardCharsets.UTF_8)){return JsonParser.parseReader(reader).getAsJsonObject();}
        catch(Exception e){throw new IllegalArgumentException("Invalid art resource "+resource,e);}
    }
}
