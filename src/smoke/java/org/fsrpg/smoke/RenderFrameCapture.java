package org.fsrpg.smoke;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.TextureFormat;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.fsrpg.config.RpgConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.stb.STBImageWrite;

/** Dev-only framebuffer capture. One image is taken for each real rendered frame, never each game tick. */
final class RenderFrameCapture {
    private static final Logger LOG = LoggerFactory.getLogger("FSRPG RENDER CAPTURE");
    private final Path evidence;
    private final List<Session> sessions = new ArrayList<>();
    private final ExecutorService writer = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "fsrpg-render-png-writer"); thread.setDaemon(true); return thread;
    });
    private final AtomicInteger pending = new AtomicInteger();
    private volatile Throwable failure;
    private Session active;
    private long renderFrames;
    private boolean finalized;
    private int originalPngCompression;
    private boolean compressionChanged;

    private static final class Session {
        final String id, source, startedAtUtc = Instant.now().toString();
        final long startNanos = System.nanoTime();
        final Supplier<JsonObject> observation;
        final List<JsonObject> frames = new ArrayList<>();
        final AtomicInteger written = new AtomicInteger();
        final AtomicLong observationNanos = new AtomicLong(), gpuCopyNanos = new AtomicLong(), pngNanos = new AtomicLong();
        final AtomicLong mapNanos = new AtomicLong(), allocationNanos = new AtomicLong(), rowCopyNanos = new AtomicLong();
        Session(String id, String source, Supplier<JsonObject> observation) {
            this.id = id; this.source = source; this.observation = observation;
        }
    }

    RenderFrameCapture(Path evidence) { this.evidence = evidence; }

    void start(String id, String source, Supplier<JsonObject> observation) throws IOException {
        assertHealthy();
        if (active != null) throw new IllegalStateException("Capture already active: " + active.id);
        if (finalized || !id.matches("[a-z0-9-]+")) throw new IllegalArgumentException("Invalid capture session " + id);
        if (sessions.stream().anyMatch(session -> session.id.equals(id))) throw new IllegalArgumentException("Duplicate capture " + id);
        if(!compressionChanged) {
            // Only the active recorder changes this process-local native setting.
            originalPngCompression=STBImageWrite.stbi_write_png_compression_level.get(0);
            STBImageWrite.stbi_write_png_compression_level.put(0,1);
            compressionChanged=true;
        }
        Files.createDirectories(evidence.resolve("screenshots/render-frames/" + id));
        active = new Session(id, source, observation); sessions.add(active);
        LOG.info("CAPTURE START {} source={} (one image per RenderFrameEvent.Post)", id, source);
    }

    void stop() {
        if (active == null) return;
        LOG.info("CAPTURE STOP {} requestedFrames={} pendingWrites={}", active.id, active.frames.size(), pending.get());
        active = null;
    }

    void frame(RenderFrameEvent.Post event) {
        renderFrames++;
        Session session = active;
        if (session == null || failure != null) return;
        Minecraft game = Minecraft.getInstance();
        if (game.level == null || game.player == null || game.getWindow().isIconified()) {
            failure = new IllegalStateException("Capture requires a visible, rendered game world"); return;
        }
        try {
            int number = session.frames.size();
            String relative = "screenshots/render-frames/" + session.id + "/frame-" + String.format(Locale.ROOT, "%06d", number) + ".png";
            Path imageFile = evidence.resolve(relative);
            JsonObject sample = new JsonObject();
            double capturedAt = (System.nanoTime() - session.startNanos) / 1_000_000_000.0;
            float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
            sample.addProperty("index", number); sample.addProperty("file", relative);
            sample.addProperty("renderFrame", renderFrames); sample.addProperty("capturedAtSeconds", capturedAt);
            sample.addProperty("gameTimeTicks", game.level.getGameTime() + (double) partial);
            sample.addProperty("renderDeltaTicks", event.getPartialTick().getGameTimeDeltaTicks());
            sample.addProperty("paused", game.isPaused());
            sample.addProperty("levelRunningNormally", game.level.tickRateManager().runsNormally());
            sample.addProperty("partialTick", partial);
            long observationStarted = System.nanoTime();
            sample.add("observation", session.observation.get()); session.frames.add(sample);
            session.observationNanos.addAndGet(System.nanoTime() - observationStarted);
            pending.incrementAndGet();
            captureImage(session, imageFile);
        } catch (Throwable error) { failure = error; }
    }

    /** Same GPU readback API as Minecraft Screenshot, using whole-row memcpy instead of its per-pixel Java loop. */
    private void captureImage(Session session, Path imageFile) {
        var target = Minecraft.getInstance().getMainRenderTarget();
        var texture = target.getColorTexture();
        if (texture == null || texture.getFormat() != TextureFormat.RGBA8)
            throw new IllegalStateException("Expected an RGBA8 main framebuffer");
        int width = target.width, height = target.height, rowBytes = Math.multiplyExact(width, 4);
        GpuBuffer buffer = RenderSystem.getDevice().createBuffer(() -> "FSRPG render-frame readback",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, (long) rowBytes * height);
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        try {
            encoder.copyTextureToBuffer(texture, buffer, 0L, () -> {
                NativeImage image = null;
                long copyStarted = System.nanoTime();
                try (var mapped = encoder.mapBuffer(buffer, true, false)) {
                    long mappedAt=System.nanoTime();session.mapNanos.addAndGet(mappedAt-copyStarted);
                    image = new NativeImage(width, height, false);
                    long allocatedAt=System.nanoTime();session.allocationNanos.addAndGet(allocatedAt-mappedAt);
                    long source = MemoryUtil.memAddress(mapped.data()), destination = image.getPointer();
                    // GPU rows start at the bottom; PNG NativeImage rows start at the top.
                    for (int row = 0; row < height; row++)
                        MemoryUtil.memCopy(source + (long) row * rowBytes, destination + (long) (height - row - 1) * rowBytes, rowBytes);
                    session.rowCopyNanos.addAndGet(System.nanoTime()-allocatedAt);
                    session.gpuCopyNanos.addAndGet(System.nanoTime() - copyStarted);
                    NativeImage copied = image;
                    writer.execute(() -> {
                        long writeStarted = System.nanoTime();
                        try (copied) {
                            // Match Screenshot's opaque alpha without delaying the render thread.
                            var pixels = MemoryUtil.memIntBuffer(copied.getPointer(), Math.multiplyExact(width, height));
                            for (int pixel = 0; pixel < pixels.limit(); pixel++) pixels.put(pixel, pixels.get(pixel) | 0xff000000);
                            copied.writeToFile(imageFile.toFile()); session.written.incrementAndGet();
                        } catch (Throwable error) { failure = error; }
                        finally { session.pngNanos.addAndGet(System.nanoTime() - writeStarted); pending.decrementAndGet(); }
                    });
                    image = null; // Ownership moved to the PNG writer.
                } catch (Throwable error) {
                    if (image != null) image.close(); failure = error; pending.decrementAndGet();
                } finally { buffer.close(); }
            }, 0);
        } catch (Throwable error) { buffer.close(); pending.decrementAndGet(); throw error; }
    }

    void assertHealthy() {
        if (failure != null) throw new IllegalStateException("Real render capture failed", failure);
    }

    /** Wait by polling from later client ticks; never block the renderer for GPU callbacks or PNG writes. */
    boolean finish() throws IOException {
        assertHealthy();
        if (active != null) throw new IllegalStateException("Capture still active");
        if (pending.get() != 0) return false;
        if (finalized) return true;
        JsonObject report = summary();
        RpgConfig.atomicWrite(evidence.resolve("render-capture.json"), RpgConfig.JSON.toJson(report));
        finalized = true; writer.shutdown();
        if(compressionChanged)STBImageWrite.stbi_write_png_compression_level.put(0,originalPngCompression);
        return true;
    }

    JsonObject summary() {
        assertHealthy();
        JsonObject result = new JsonObject();
        result.addProperty("schemaVersion", 1); result.addProperty("captureEvent", "RenderFrameEvent.Post");
        result.addProperty("clock", "System.nanoTime; seconds relative to each capture start");
        result.addProperty("pixelTransfer", "RGBA8 GPU readback; vertically flipped whole-row memcpy; opaque alpha and PNG off render thread");
        result.addProperty("minimumSourceFps", 30); result.addProperty("renderFramesObserved", renderFrames);
        result.addProperty("pngCompressionLevel",1);
        result.addProperty("pendingWrites", pending.get());
        JsonArray clips = new JsonArray();
        for (Session session : sessions) {
            JsonObject clip = new JsonObject();
            clip.addProperty("id", session.id); clip.addProperty("source", session.source);
            clip.addProperty("startedAtUtc", session.startedAtUtc); clip.addProperty("capturedFrames", session.frames.size());
            clip.addProperty("writtenFrames", session.written.get());
            double elapsed = session.frames.size() > 1 ? number(session.frames.getLast(), "capturedAtSeconds") - number(session.frames.getFirst(), "capturedAtSeconds") : 0;
            clip.addProperty("captureDurationSeconds", elapsed);
            clip.addProperty("sourceFps", elapsed > 0 ? (session.frames.size() - 1) / elapsed : 0);
            int frameCount = Math.max(1, session.frames.size());
            clip.addProperty("averageObservationCpuMillis", session.observationNanos.get() / (frameCount * 1_000_000.0));
            clip.addProperty("averageGpuCopyCpuMillis", session.gpuCopyNanos.get() / (frameCount * 1_000_000.0));
            clip.addProperty("averageMapCpuMillis",session.mapNanos.get()/(frameCount*1_000_000.0));
            clip.addProperty("averageAllocationCpuMillis",session.allocationNanos.get()/(frameCount*1_000_000.0));
            clip.addProperty("averageRowCopyCpuMillis",session.rowCopyNanos.get()/(frameCount*1_000_000.0));
            clip.addProperty("averagePngWorkerMillis", session.pngNanos.get() / (frameCount * 1_000_000.0));
            double maxGap = 0; int subtickPairs = 0, changedPoses = 0, changedRigPairs = 0;
            for (int i = 1; i < session.frames.size(); i++) {
                JsonObject previous = session.frames.get(i - 1), next = session.frames.get(i);
                maxGap = Math.max(maxGap, number(next, "capturedAtSeconds") - number(previous, "capturedAtSeconds"));
                double a = number(previous, "gameTimeTicks"), b = number(next, "gameTimeTicks");
                if (Math.floor(a) == Math.floor(b) && Math.abs(a - b) > .00001) subtickPairs++;
                if (!previous.get("observation").equals(next.get("observation"))) changedPoses++;
                var oldRig = previous.getAsJsonObject("observation").get("rig");
                var newRig = next.getAsJsonObject("observation").get("rig");
                if (oldRig != null && newRig != null && !oldRig.equals(newRig)) changedRigPairs++;
            }
            clip.addProperty("maximumFrameGapSeconds", maxGap); clip.addProperty("distinctSamplesWithinGameTick", subtickPairs);
            clip.addProperty("changedObservationPairs", changedPoses);
            clip.addProperty("changedRigPairs", changedRigPairs);
            clip.add("frames", RpgConfig.JSON.toJsonTree(session.frames)); clips.add(clip);
            LOG.info("CAPTURE {} frames={} duration={} sourceFPS={} subtickPairs={} written={}", session.id, session.frames.size(), elapsed,
                    elapsed > 0 ? (session.frames.size() - 1) / elapsed : 0, subtickPairs, session.written.get());
        }
        result.add("clips", clips); return result;
    }

    private static double number(JsonObject object, String key) { return object.get(key).getAsDouble(); }
}
