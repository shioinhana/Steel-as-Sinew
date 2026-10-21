package org.fsrpg.client;

import org.fsrpg.combat.AttackAim;
import org.fsrpg.combat.SpearAimField;

/** The whole invisible virtual viewport maps to a smaller physical aiming envelope. */
public final class SpearAimMapping {
    public record Cursor(double x,double y){
        public Cursor{if(!Double.isFinite(x)||!Double.isFinite(y)||Math.abs(x)>1||Math.abs(y)>1)throw new IllegalArgumentException("Normalized cursor must stay in [-1,1]");}
    }
    public static Cursor move(Cursor from,double dx,double dy,double width,double height){
        if(!Double.isFinite(dx)||!Double.isFinite(dy)||!Double.isFinite(width)||!Double.isFinite(height)||width<=0||height<=0)throw new IllegalArgumentException("Expected finite mouse input and positive viewport");
        return new Cursor(Math.clamp(from.x()+2*dx/width,-1,1),Math.clamp(from.y()+2*dy/height,-1,1));
    }
    public static AttackAim.Point direction(Cursor cursor,double bodyYaw,double targetPitch,double scale){
        if(!Double.isFinite(bodyYaw)||!Double.isFinite(targetPitch)||!Double.isFinite(scale)||scale<=0||scale>1)throw new IllegalArgumentException("Expected finite heading and aiming scale in (0,1]");
        double yawLimit=SpearAimField.MAX_YAW_DEGREES*scale,pitchLimit=SpearAimField.MAX_PITCH_DEGREES*scale;
        double center=Math.clamp(targetPitch,-pitchLimit,pitchLimit);
        double pitch=cursor.y()<0?center+(-pitchLimit-center)*-cursor.y():center+(pitchLimit-center)*cursor.y();
        return new AttackAim.Point(AttackAim.wrap(bodyYaw+cursor.x()*yawLimit),pitch,64);
    }
    private SpearAimMapping(){}
}
