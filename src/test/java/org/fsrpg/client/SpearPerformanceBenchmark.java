package org.fsrpg.client;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import org.fsvoxel.render.ModelLoader;
import org.fsrpg.combat.AttackAim;
import org.fsrpg.combat.SpearAimField;

/** Deterministic real-rig solver workload; timing excludes fixture construction and final FK. */
public final class SpearPerformanceBenchmark {
    public static void main(String[] args) throws Exception {
        var model=ModelLoader.load(Path.of("src/main/resources/assets/fsrpg/fsvoxel/models/spear.fsvoxel"));
        var thread=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        thread.setThreadAllocatedMemoryEnabled(true);
        var report=new JsonObject();var groups=new JsonArray();report.add("groups",groups);
        try(var bytes=SpearAimPose.class.getResourceAsStream("SpearAimPose.class")) {
            report.addProperty("solverSha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.readAllBytes())));
        }
        int frames=Integer.getInteger("fsrpg.benchmarkFrames",120),repetitions=Integer.getInteger("fsrpg.benchmarkRepetitions",3);
        report.addProperty("framesPerTrack",frames);report.addProperty("repetitions",repetitions);
        report.addProperty("sampleHz",60);report.addProperty("scope","Full SpearAimPose.apply, real source rig and production transported history, finite forward-field aim; offline timing does not replace game testing");
        for(String action:List.of("spear_idle","spear_thrust","spear_sweep"))for(boolean movingAim:List.of(false,true)) {
            var elapsed=new ArrayList<Long>();long allocated=0;var digest=MessageDigest.getInstance("SHA-256");
            for(int repetition=0;repetition<repetitions;repetition++) {
                var playback=new PoseLayers.Playback(model.rig(),action);var history=new SpearMotion.State();
                for(int frame=0;frame<frames;frame++) {
                    double seconds=frame/60.,tick=frame/3.;boolean ready=action.equals("spear_idle");
                    var source=playback.sample(seconds%playback.clip().duration(),seconds,tick,0,0,.25f,55).pose();
                    var raw=model.worldMatrices(source);
                    raw=ready?SpearMotion.readySource(raw,-12,22,50):SpearMotion.underarmSource(raw);
                    double yaw=movingAim?SpearAimField.MAX_YAW_DEGREES*Math.sin(seconds*Math.PI):0;
                    double pitch=movingAim?SpearAimField.MAX_PITCH_DEGREES*Math.sin(seconds*Math.PI*2):0;
                    var aim=new AttackAim.Point(yaw,pitch,4.5/(Math.cos(Math.toRadians(yaw))*Math.cos(Math.toRadians(pitch))));
                    var seed=history.prepare(raw,tick,ready?90:0,ready?SpearAimPose.authoredFidelity(aim,0):1);
                    var limits=new SpearConstraints.Limits(135,120,1.5f,85,18,true).withCarry(ready);
                    long beforeBytes=thread.getThreadAllocatedBytes(Thread.currentThread().threadId()),start=System.nanoTime();
                    var solved=SpearAimPose.apply(model,source,aim,0,1.62f,seed.rear(),seed.chest(),seed.actual(),seed.continuity(),seed.authoredMotion(),limits);
                    long duration=System.nanoTime()-start,afterBytes=thread.getThreadAllocatedBytes(Thread.currentThread().threadId());
                    if(repetition>0){elapsed.add(duration);allocated+=afterBytes-beforeBytes;}
                    var world=model.worldMatrices(solved);history.commit(world,tick);
                    if(repetition==repetitions-1)for(String bone:List.of("body","head","right_arm","right_forearm","right_hand","left_arm","left_forearm","left_hand"))
                        for(float value:world.get(bone).get(new float[16])) {
                            int bits=Float.floatToIntBits(value);digest.update((byte)bits);digest.update((byte)(bits>>>8));digest.update((byte)(bits>>>16));digest.update((byte)(bits>>>24));
                        }
                }
            }
            long[] ordered=elapsed.stream().mapToLong(Long::longValue).sorted().toArray();
            var group=new JsonObject();group.addProperty("action",action);group.addProperty("movingAim",movingAim);
            group.addProperty("samples",ordered.length);group.addProperty("meanMs",Arrays.stream(ordered).average().orElseThrow()/1e6);
            group.addProperty("p50Ms",ordered[ordered.length/2]/1e6);group.addProperty("p95Ms",ordered[(int)(ordered.length*.95)]/1e6);
            group.addProperty("maxMs",ordered[ordered.length-1]/1e6);group.addProperty("allocatedBytesPerSolve",allocated/(double)ordered.length);
            group.addProperty("finalFrameMatricesSha256",HexFormat.of().formatHex(digest.digest()));groups.add(group);
            System.out.println(group);
        }
        Path output=Path.of(args.length>0?args[0]:"verification/spear-performance-20261016/micro/report.json");
        Files.createDirectories(output.toAbsolutePath().getParent());Files.writeString(output,new GsonBuilder().setPrettyPrinting().create().toJson(report));
    }
}
