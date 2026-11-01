package org.fsrpg.client;

/** Render-time pelvis following with alternate, briefly lifted turning feet. */
public final class SpearTurn {
    private SpearTurn() {}
    public record Feet(float pelvisYaw, float rightYaw, float leftYaw, float rightLift, float leftLift,
                       float rightSupport, float leftSupport) {}
    public record Settings(float responseTicks,float stepTicks,float stepRadians,float liftPixels,
                           float maximumTwistRadians,float directTurnWeight) {
        public static final Settings DEFAULT=new Settings(3.2f,6,.49f,.9f,(float)Math.toRadians(45),.35f);
        public Settings(float responseTicks,float stepTicks,float stepRadians,float liftPixels) {
            this(responseTicks,stepTicks,stepRadians,liftPixels,DEFAULT.maximumTwistRadians,DEFAULT.directTurnWeight);
        }
        public Settings {
            if(!Float.isFinite(responseTicks)||responseTicks<1||responseTicks>10
                    ||!Float.isFinite(stepTicks)||stepTicks<3||stepTicks>12
                    ||!Float.isFinite(stepRadians)||stepRadians<.1f||stepRadians>.8f
                    ||!Float.isFinite(liftPixels)||liftPixels<.2f||liftPixels>1.6f
                    ||!Float.isFinite(maximumTwistRadians)||maximumTwistRadians<Math.toRadians(10)||maximumTwistRadians>Math.toRadians(90)
                    ||!Float.isFinite(directTurnWeight)||directTurnWeight<0||directTurnWeight>1)
                throw new IllegalArgumentException("Invalid spear turning presentation settings");
        }
    }
    public static final class State {
        private double tick=Double.NaN, stepTick;
        private float pelvis, right, left, start, goal, actorYaw;
        private int swing=-1, next;
        private long hardTurns;
        private Feet last=new Feet(0,0,0,0,0,1,1);
        public Feet advance(double now,float chestYaw,float bodyYaw,float moving) {
            return advance(now,chestYaw,bodyYaw,moving,Settings.DEFAULT);
        }
        public Feet advance(double now,float chestYaw,float bodyYaw,float moving,Settings settings) {
            if(!Double.isFinite(now)||!Float.isFinite(chestYaw)||!Float.isFinite(bodyYaw)||!Float.isFinite(moving))
                throw new IllegalArgumentException("Non-finite spear turn");
            if(now==tick)return last;
            if(Double.isNaN(tick)||now<tick) {
                // The first frame owns a stance, not a turn from an imaginary world-forward
                // pose. This also keeps a sword's authored side-on carry out of the twist limit.
                tick=now; actorYaw=bodyYaw; pelvis=right=left=wrap(chestYaw); swing=-1;
                return last=new Feet(pelvis,right,left,0,0,1,1);
            }
            float dt=(float)Math.min(2,Math.max(0,now-tick)); tick=now;
            float root=(float)Math.toRadians(org.fsrpg.combat.AttackAim.wrap(bodyYaw-actorYaw)); actorYaw=bodyYaw;
            // A modest share follows the root immediately. The rest retains the soles'
            // world heading and is released by alternating steps rather than a frozen waist.
            float retainedRoot=root*(1-settings.directTurnWeight());
            pelvis-=retainedRoot; right-=retainedRoot; left-=retainedRoot; start-=retainedRoot; goal-=retainedRoot;
            float error=wrap(chestYaw-pelvis);
            boolean hardTurn=Math.abs(error)>settings.maximumTwistRadians();
            if(hardTurn) {
                // Bound the additional stance lag in this very frame, even for a 180-degree
                // root change. Both feet share the correction, so the pelvis cannot detach.
                float correction=error-Math.copySign(settings.maximumTwistRadians(),error);
                pelvis+=correction; right+=correction; left+=correction; start+=correction; goal+=correction;
                error=wrap(chestYaw-pelvis); hardTurns++;
            }
            pelvis+=Math.clamp(error*(1-(float)Math.exp(-dt/settings.responseTicks())),-dt*.105f,dt*.105f);
            float turns=pelvis-wrap(pelvis);
            pelvis-=turns; right-=turns; left-=turns; start-=turns; goal-=turns;
            if(moving>=.9999f) {
                // Walking already provides alternate swings; only rotate the
                // stance width, preserving the actual world travel direction.
                right=left=pelvis;swing=-1;
                return last=new Feet(pelvis,right,left,0,0,1,1);
            }
            if(swing<0 && (hardTurn || Math.max(Math.abs(wrap(pelvis-right)),Math.abs(wrap(pelvis-left)))>Math.toRadians(4))) {
                swing=next; next=1-next; stepTick=now; start=swing==0?right:left;
                goal=start+Math.clamp(wrap(chestYaw-start),-settings.stepRadians(),settings.stepRadians());
            }
            float lift=0;
            if(swing>=0) {
                float u=Math.clamp((float)((now-stepTick)/settings.stepTicks()),0,1), smooth=u*u*(3-2*u);
                float yaw=start+(goal-start)*smooth;
                if(swing==0)right=yaw; else left=yaw;
                lift=settings.liftPixels()*(float)Math.pow(Math.sin(Math.PI*u),2);
                if(u>=1)swing=-1;
            }
            // A partial start must not cancel the stepping clock. Otherwise a
            // tiny movement pulse followed by stop drops the lifted sole at once.
            // Both directions of the blend retain that same physical step.
            float walk=Math.clamp(moving,0,1);walk=walk*walk*(3-2*walk);
            return last=new Feet(pelvis,right+wrap(pelvis-right)*walk,left+wrap(pelvis-left)*walk,
                    swing==0?lift*(1-walk):0,swing==1?lift*(1-walk):0,swing==0?0:1,swing==1?0:1);
        }
        /** Read-only presentation diagnostic; increments once per rendered turn correction. */
        public long hardTurns() { return hardTurns; }
    }
    private static float wrap(float radians) { return (float)Math.atan2(Math.sin(radians),Math.cos(radians)); }
}
