package org.fsrpg.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.concurrent.CopyOnWriteArrayList;

/** Server snapshots are presentation data; client state never decides combat or quest results. */
public final class ClientState {
    private static JsonObject snapshot = new JsonObject();
    private static long revision;
    private static long acceptCalls,acceptNanos,acceptMaxNanos;
    private static final List<Consumer<JsonObject>> observers = new CopyOnWriteArrayList<>();
    private ClientState() {}

    public static void accept(String json) {
        long started=System.nanoTime();
        try {
            JsonElement value = JsonParser.parseString(json);
            if (!value.isJsonObject()) throw new IllegalArgumentException("Expected RPG snapshot object");
            snapshot = value.getAsJsonObject();
            revision++;
            for (var observer : observers) observer.accept(snapshot.deepCopy());
        } finally {
            long elapsed=System.nanoTime()-started;
            acceptCalls++;acceptNanos+=elapsed;acceptMaxNanos=Math.max(acceptMaxNanos,elapsed);
        }
    }
    public static void resetPerfCounters(){acceptCalls=acceptNanos=acceptMaxNanos=0;}
    public static long[] perfSnapshot(){return new long[]{acceptCalls,acceptNanos,acceptMaxNanos};}
    public static void acceptTargetLock(String json) {
        JsonElement value=JsonParser.parseString(json);
        if(!value.isJsonObject())throw new IllegalArgumentException("Expected target lock object");
        snapshot.add("targetLock",value.getAsJsonObject());revision++;
    }
    /** Observe each received authoritative snapshot, even when several phases arrive in one render frame. */
    public static AutoCloseable observeSnapshots(Consumer<JsonObject> observer) {
        observers.add(observer); return () -> observers.remove(observer);
    }
    public static void clear() { snapshot = new JsonObject(); revision++; }
    public static long revision() { return revision; }
    public static JsonObject snapshot() { return snapshot; }
    public static boolean drawn() { return bool(snapshot, "drawn", false); }
    public static boolean canEdit() { return bool(snapshot, "canEdit", false); }
    public static List<JsonObject> skills() { return objects(snapshot, "skills"); }
    public static List<JsonObject> quests() { return objects(snapshot, "quests"); }
    public static JsonObject collection() { return object(snapshot, "collection"); }
    public static JsonObject object(JsonObject parent, String key) {
        JsonElement value = parent.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
    }
    public static String text(JsonObject object, String key, String fallback) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : fallback;
    }
    public static double number(JsonObject object, String key, double fallback) {
        try { return object.has(key) ? object.get(key).getAsDouble() : fallback; }
        catch (RuntimeException ignored) { return fallback; }
    }
    public static boolean bool(JsonObject object, String key, boolean fallback) {
        try { return object.has(key) ? object.get(key).getAsBoolean() : fallback; }
        catch (RuntimeException ignored) { return fallback; }
    }
    public static List<JsonObject> objects(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonArray()) return List.of();
        List<JsonObject> result = new ArrayList<>();
        for (JsonElement entry : value.getAsJsonArray()) if (entry.isJsonObject()) result.add(entry.getAsJsonObject());
        return List.copyOf(result);
    }
}
