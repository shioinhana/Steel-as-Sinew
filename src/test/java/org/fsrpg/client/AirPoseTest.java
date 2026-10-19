package org.fsrpg.client;

import java.util.Map;

/** Ground/air transitions and authoritative action overrides share exactly one presentation selector. */
public final class AirPoseTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    public static void main(String[] args){
        for(String weapon:new String[]{"rapier","dual_blade","spear","bow"}) {
            var state=new AirPose.State<Map<String,Integer>>();
            var walk=Map.of("body",11,"right_arm",22,"left_leg",33);var unwantedFall=Map.of("body",0,"right_arm",99,"left_leg",-9);
            check(state.sample(weapon,false,"READY",walk)==walk,"Ground stance must be sampled naturally");
            for(int frame=0;frame<120;frame++)check(state.sample(weapon,true,"READY",unwantedFall)==walk,"Airborne idle replaces the actual takeoff pose");
            check(state.holding(),"No-action air should report a retained pose");
            Map<String,Integer> end=walk;
            for(String phase:new String[]{"WINDUP","ACTIVE","RECOVERY"}) {
                for(int frame=0;frame<20;frame++){
                    var attack=Map.of("body",frame+4,"right_arm",frame+15,"left_leg",frame+28);end=attack;
                    check(state.sample(weapon,true,phase,attack)==attack,"An actual airborne attack must continue its authored frames");
                    check(!state.holding(),"An advancing actual action must not be marked idle-held");
                    check(state.takeoff()==walk,"Advancing upper attack loses the original airborne lower-body source");
                }
            }
            for(int frame=0;frame<120;frame++)check(state.sample(weapon,true,"READY",unwantedFall)==end,"Attack completion in air returns to a forced default instead of its last submitted attack pose");
            var guard=Map.of("body",2,"right_arm",3,"left_leg",4);check(state.sample(weapon,true,"GUARDING",guard)==guard,"Actual explicit guard cannot override retained air pose");
            var hit=Map.of("body",3,"right_arm",4,"left_leg",5);check(state.sample(weapon,true,"STUNNED",hit)==hit,"Actual hit reaction cannot override retained air pose");
            check(state.sample(weapon,true,"READY",unwantedFall)==hit,"Hit reaction should remain the latest air pose after it ends");
            check(state.sample(weapon,false,"READY",walk)==walk&&!state.holding(),"Landing must resume current natural locomotion");
            check(state.sample("replacement-weapon",true,"READY",unwantedFall)==unwantedFall,"Equipment changes must not carry the old rig into the new weapon");
        }
        var presented=new AirPose.State<String>();presented.sample("sword",false,"READY","takeoff");presented.sample("sword",true,"ACTIVE","raw-unfitted-action");presented.presented("actual-composited-action");
        check(presented.sample("sword",true,"READY","unwanted-idle").equals("actual-composited-action"),"Attack completion must retain the final submitted upper/lower composition");
        var initial=new AirPose.State<Integer>();check(initial.sample("new-air-actor",true,"READY",17)==17,"Actor first seen in air has no nonexistent old pose");
        System.out.println("AirPoseTest passed: "+checks+" checks (four weapons, takeoff stance, active progression, endpoint hold, guard/hit overrides, landing and equipment changes)");
    }
}
