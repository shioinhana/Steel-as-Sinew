package org.fsrpg.animation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Arrays;
import org.fsvoxel.render.ModelLoader;

/** Parse with the shipped engine and sample actual FK, including FP and persistent dual ready poses. */
public final class LegacyAssetTest {
    public static void main(String[] args) throws Exception {
        Path assets=Path.of("src/main/resources/assets/fsrpg/fsvoxel/models");
        Map<String,List<String>> required=Map.of(
            "rapier",List.of("rapier_idle","rapier_draw","rapier_guard","rapier_guard_heavy","rapier_charge","rapier_full_charge",
                "legacy_rapier_attack1_upper","legacy_rapier_attack2_upper","legacy_rapier_attack3_upper","legacy_rapier_attack4_upper",
                "legacy_rapier_rapier_thrust_hit_upper","legacy_rapier_rapier_thrust_miss_upper","legacy_rapier_attack_dual_upper",
                "legacy_rapier_rapier_dodge_forward","legacy_rapier_rapier_dodge_back_left"),
            "dual_blade",List.of("dual_idle_00","dual_idle_12","dual_idle_21","dual_idle_22","dual_draw","dual_guard",
                "legacy_dual_blade_attack_1_upper","legacy_dual_blade_attack2_upper","legacy_dual_blade_attack3_upper",
                "legacy_dual_blade_attack4_upper","legacy_dual_blade_attack_dual_upper"),
            "bow",List.of("bow_draw","bow_rapid_idle","bow_snipe_idle","bow_rapid_charge","bow_snipe_charge",
                "bow_rapid_release","bow_snipe_release","bow_bash"));
        int clips=0,frames=0;
        for(String kind:required.keySet())for(boolean fp:new boolean[]{false,true}) {
            Path file=assets.resolve("legacy_"+kind+(fp?"_fp":"")+".fsvoxel");
            var model=ModelLoader.load(file);var rig=model.rig();
            for(String action:required.get(kind))if(!rig.clips().containsKey(action))throw new AssertionError(file+" missing "+action);
            if(!fp)for(var node:model.nodes().values())if(!node.mesh().faces().isEmpty())throw new AssertionError("TP must use the player's skin/armor: "+node.id());
            for(var node:model.nodes().values())if(node.id().endsWith("_item")&&!node.mesh().faces().isEmpty())throw new AssertionError("Weapon mesh imported: "+node.id());
            for(var clip:rig.clips().values()) {
                if(!clip.tags().contains("legacy_011")||!clip.rotationMode().equals("quaternion"))throw new AssertionError("Unmapped clip "+clip.id());
                for(var keys:clip.tracks().values())for(int i=0;i<keys.size();i++) {
                    var scale=keys.get(i).scale();
                    if(scale!=null&&(scale.x()<=0||scale.y()<=0||scale.z()<=0))throw new AssertionError("Nonpositive scale: "+clip.id());
                    if(i>0&&keys.get(i).time()-keys.get(i-1).time()>1.0/30+1e-7)throw new AssertionError("Below 30Hz: "+clip.id());
                }
                for(double seconds:new double[]{0,1.0/120,clip.duration()*.23,clip.duration()*.5,clip.duration()*.81,clip.duration()}) {
                    var pose=rig.sampleLocal(List.of(clip),seconds,Map.of());
                    var world=model.worldMatrices(pose);
                    if(!world.containsKey("right_item"))throw new AssertionError("No real item locator: "+file);
                    for(var matrix:world.values())for(float value:matrix.get(new float[16]))
                        if(!Float.isFinite(value))throw new AssertionError("Nonfinite FK: "+clip.id());
                    frames++;
                }
                clips++;
            }
            try(var bundled=LegacyAssetTest.class.getResourceAsStream("/assets/fsrpg/fsvoxel/models/"+file.getFileName())) {
                if(bundled==null || !Arrays.equals(Files.readAllBytes(file),bundled.readAllBytes()))
                    throw new AssertionError("Bundled model differs from source: "+file);
            }
            Path config=Path.of("run/client/config/fsrpg/models").resolve(file.getFileName());
            if(Files.exists(config) && Files.mismatch(file,config)!=-1)throw new AssertionError("Config copy differs: "+file);
            System.out.println("Loaded and sampled "+file.getFileName()+": "+rig.clips().size()+" clips");
        }
        System.out.println("LegacyAssetTest passed: "+clips+" imported playback nodes / "+frames+" actual engine FK samples");
    }
}
