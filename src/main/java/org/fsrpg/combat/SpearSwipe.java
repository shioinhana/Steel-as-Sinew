package org.fsrpg.combat;

/** World-space tangential spear impulses, without an invented forward push or ground bounce. */
public final class SpearSwipe {
    private SpearSwipe() {}
    public record Vector(double x,double y,double z) {
        public static final Vector ZERO=new Vector(0,0,0);
        public Vector {if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z))throw new IllegalArgumentException("nonfinite swipe vector");}
        public double length(){return Math.sqrt(x*x+y*y+z*z);}
        public boolean swept(){return length()>1e-8;}
        public Vector normalized(){double length=length();return length<1e-8?ZERO:new Vector(x/length,y/length,z/length);}
    }
    public record Impulse(Vector velocity,boolean applied,boolean downwardSlam) {}

    /** Derivative of the admitted yaw/pitch path at this sampled shaft, using Minecraft world axes. */
    public static Vector tangent(AttackAim.Point from,AttackAim.Point to,AttackAim.Point at) {
        double yaw=Math.toRadians(at.yaw()),pitch=Math.toRadians(at.pitch());
        double dyaw=Math.toRadians(AttackAim.wrap(to.yaw()-from.yaw())),dpitch=Math.toRadians(to.pitch()-from.pitch());
        return new Vector(-Math.cos(yaw)*Math.cos(pitch)*dyaw+Math.sin(yaw)*Math.sin(pitch)*dpitch,
                -Math.cos(pitch)*dpitch,-Math.sin(yaw)*Math.cos(pitch)*dyaw-Math.cos(yaw)*Math.sin(pitch)*dpitch).normalized();
    }

    public static Impulse apply(Vector swipe,double knockback,double multiplier,double resistance,boolean grounded,Vector old) {
        SkillDefinition.checkNumber(knockback,"swipe knockback",0,128);
        SkillDefinition.checkNumber(multiplier,"swipe knockback multiplier",0,1);
        SkillDefinition.checkNumber(resistance,"knockback resistance",0,1);
        var direction=swipe.normalized();double strength=knockback*multiplier*(1-resistance);
        double x=direction.x()*strength,y=direction.y()*strength,z=direction.z()*strength;
        if(grounded&&y<0)y=0;
        if(strength<=0||Math.abs(x)+Math.abs(y)+Math.abs(z)<1e-9)return new Impulse(old,false,false);
        boolean slam=!grounded&&y<0;
        double vertical=slam?Math.min(old.y(),0)+y:y>0?Math.max(old.y(),0)+y:old.y();
        return new Impulse(new Vector(old.x()*.5+x,vertical,old.z()*.5+z),true,slam);
    }
}
