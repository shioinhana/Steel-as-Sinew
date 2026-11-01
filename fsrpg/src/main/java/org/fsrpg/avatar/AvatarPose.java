package org.fsrpg.avatar;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.joml.Matrix4f;

/** Binds named appearance bones without changing combat or the animation clock. */
public final class AvatarPose {
    private AvatarPose() {}
    public static final Matrix4f TO_NATIVE = new Matrix4f().translation(0,1.5f,0).scale(-1,-1,1);
    public static String binding(String name) {
        String n=name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return switch(n) {
            case "head", "hat" -> "head";
            case "body", "torso", "chest", "jacket" -> "body";
            case "hip", "hips", "pelvis", "waist" -> "hip";
            case "rightarm", "armright", "rarm", "rightupperarm", "rightsleeve" -> "right_arm";
            case "leftarm", "armleft", "larm", "leftupperarm", "leftsleeve" -> "left_arm";
            case "rightforearm", "rightlowerarm", "forearmright" -> "right_forearm";
            case "leftforearm", "leftlowerarm", "forearmleft" -> "left_forearm";
            case "righthand", "handright" -> "right_hand";
            case "lefthand", "handleft" -> "left_hand";
            case "rightleg", "legright", "rleg", "rightthigh", "rightpants" -> "right_leg";
            case "leftleg", "legleft", "lleg", "leftthigh", "leftpants" -> "left_leg";
            case "rightshin", "rightcalf", "rightlowerleg" -> "right_shin";
            case "leftshin", "leftcalf", "leftlowerleg" -> "left_shin";
            case "rightfoot", "footright" -> "right_foot";
            case "leftfoot", "footleft" -> "left_foot";
            default -> "";
        };
    }
    public static void validate(AvatarDocument document) {
        var present=new java.util.HashSet<String>();
        for(var b:document.bones())present.add(binding(b.name()));
        for(String name:Set.of("head","body","right_arm","left_arm","right_leg","left_leg"))
            if(!present.contains(name))throw new IllegalArgumentException("Player avatar is missing named bone: "+name);
    }
    /** Native-space rest deltas for the six base parts, plus optional action deltas for articulated limbs. */
    public static List<Matrix4f> sample(AvatarDocument document, Map<String,Matrix4f> nativeDeltas,
                                       Map<String,Matrix4f> actionDeltas) {
        var rest=document.restWorld();var result=new ArrayList<Matrix4f>();
        for(int i=0;i<document.bones().size();i++) {
            var bone=document.bones().get(i);String key=binding(bone.name());
            Matrix4f delta=nativeDeltas.get(key);
            // Articulated children follow the solved joint, rather than rigid upper-arm motion.
            if(actionDeltas.containsKey(key))result.add(new Matrix4f(TO_NATIVE).mul(actionDeltas.get(key)).mul(rest.get(i)));
            else if(delta!=null)result.add(new Matrix4f(delta).mul(TO_NATIVE).mul(rest.get(i)));
            else if(bone.parent()>=0)result.add(new Matrix4f(result.get(bone.parent())).mul(bone.local()));
            else result.add(new Matrix4f(TO_NATIVE).mul(bone.local()));
        }
        return List.copyOf(result);
    }
}
