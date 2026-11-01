package org.fsrpg.movement;

/** Pure movement formulas. Values are blocks/tick except the explicitly authored old curves, which use m/s. */
public final class FootworkMath {
    private FootworkMath(){}
    public record Motion(double x,double y,double z){
        public Motion{if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z))throw new IllegalArgumentException("Nonfinite motion");}
        public double horizontalSpeedMps(){return Math.hypot(x,z)*20.0;}
    }
    /**
     * 飞鸿: transform the component of horizontal velocity along the intended direction:
     * {@code x <= 0 ? 0.5x : x + ln(x) + 0.5x} in m/s. Vertical and perpendicular components stay.
     */
    public static Motion swan(Motion velocity,double x,double z,double linear){
        double length=Math.hypot(x,z);if(!Double.isFinite(length)||length<1e-9)throw new IllegalArgumentException("Missing movement direction");
        if(!Double.isFinite(linear)||linear<0)throw new IllegalArgumentException("Invalid flying swan curve");
        double dx=x/length,dz=z/length;
        double speed=(velocity.x*dx+velocity.z*dz)*20;
        if(!Double.isFinite(speed))throw new IllegalArgumentException("Nonfinite flying swan speed");
        double next=speed<=0?linear*speed:speed+Math.log(speed)+linear*speed;
        if(!Double.isFinite(next))throw new IllegalArgumentException("Nonfinite flying swan result");
        double delta=(next-speed)/20;
        return new Motion(velocity.x+dx*delta,velocity.y,velocity.z+dz*delta);
    }
    /** Adds a fixed horizontal m/s impulse along a world-space direction; a zero direction is a no-op. */
    public static Motion impulse(Motion velocity,double x,double z,double mps){
        if(!Double.isFinite(mps)||mps<0)throw new IllegalArgumentException("Invalid impulse");
        double length=Math.hypot(x,z);
        if(!Double.isFinite(length))throw new IllegalArgumentException("Nonfinite direction");
        if(length<1e-9||mps==0)return velocity;
        return new Motion(velocity.x+x/length*mps/20,velocity.y,velocity.z+z/length*mps/20);
    }
    /** Charged jump scales launch velocity by sqrt(height multiplier), since height is proportional to v^2. */
    public static double chargedJumpVelocity(double launchVelocity,double heightMultiplier){
        if(!Double.isFinite(launchVelocity)||launchVelocity<0)throw new IllegalArgumentException("Invalid launch velocity");
        if(!Double.isFinite(heightMultiplier)||heightMultiplier<1)throw new IllegalArgumentException("Invalid charged jump multiplier");
        return launchVelocity*Math.sqrt(heightMultiplier);
    }
    /** Wall kick reverses the exact horizontal velocity and keeps the vertical launch. */
    public static Motion wallReverse(Motion before,double launchVelocity){
        if(!Double.isFinite(launchVelocity)||launchVelocity<0)throw new IllegalArgumentException("Invalid launch velocity");
        return new Motion(-before.x,launchVelocity,-before.z);
    }
    /**
     * Converts the "drag loss multiplier" (0 = no loss, 1 = vanilla) into the post-travel correction
     * for vanilla's 0.91 horizontal air multiplier: 0.5 halves the loss (0.91 -> 0.955).
     */
    public static double airDragScale(double lossFactor){
        if(!Double.isFinite(lossFactor)||lossFactor<0||lossFactor>1)throw new IllegalArgumentException("Invalid drag loss factor");
        return (1-(1-.91)*lossFactor)/.91;
    }
}
