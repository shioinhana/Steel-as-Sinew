package org.fsrpg.client;

/** Render-time pelvis following with alternate, briefly lifted turning feet. */
public final class SpearTurn {
    private SpearTurn() {}
    public record Feet(float pelvisYaw, float rightYaw, float leftYaw, float rightLift, float leftLift,
                       float rightSupport, float leftSupport) {}
    public record Settings(float responseTicks,float stepTicks,float stepRadians,float liftPixels) {
        public static final Settings DEFAULT=new Settings(3.2f,6,.49f,.9f);
        public Settings {
            if(!Float.isFinite(responseTicks)||responseTicks<1||responseTicks>10
                    ||!Float.isFinite(stepTicks)||stepTicks<3||stepTicks>12
                    ||!Float.isFinite(stepRadians)||stepRadians<.1f||stepRadians>.8f
                    ||!Float.isFinite(liftPixels)||liftPixels<.2f||liftPixels>1.6f)
                throw new IllegalArgumentException("Invalid spear turning presentation settings");
        }
    }
    public static final class State {
        private double tick=Double.NaN, stepTick;
        private float pelvis, right, left, start, goal, actorYaw;
        private int swing=-1, next;
        private Feet last=new Feet(0,0,0,0,0,1,1);
        public Feet advance(double now,float chestYaw,float bodyYaw,float moving) {
            return advance(now,chestYaw,bodyYaw,moving,Settings.DEFAULT);
        }
        public Feet advance(double now,float chestYaw,float bodyYaw,float moving,Settings settings) {
            if(!Double.isFinite(now)||!Float.isFinite(chestYaw)||!Float.isFinite(bodyYaw)||!Float.isFinite(moving))
                throw new IllegalArgumentException("Non-finite spear turn");
            if(now==tick)return last;
            if(Double.isNaN(tick)||now<tick) {
                tick=now; actorYaw=bodyYaw; pelvis=right=left=0; swing=-1;
                return last=new Feet(0,0,0,0,0,1,1);
            }
            float dt=(float)Math.min(2,Math.max(0,now-tick)); tick=now;
            float root=(float)Math.toRadians(org.fsrpg.combat.AttackAim.wrap(bodyYaw-actorYaw)); actorYaw=bodyYaw;
            pelvis-=root; right-=root; left-=root; start-=root; goal-=root;
            float error=wrap(chestYaw-pelvis);
            pelvis+=Math.clamp(error*(1-(float)Math.exp(-dt/settings.responseTicks())),-dt*.105f,dt*.105f);
            if(moving>.001f) {
                // Walking already provides alternate swings; only rotate the
                // stance width, preserving the actual world travel direction.
                right=left=pelvis; swing=-1;
                return last=new Feet(pelvis,right,left,0,0,1,1);
            }
            if(swing<0 && Math.max(Math.abs(wrap(pelvis-right)),Math.abs(wrap(pelvis-left)))>Math.toRadians(4)) {
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
            return last=new Feet(pelvis,right,left,swing==0?lift:0,swing==1?lift:0,swing==0?0:1,swing==1?0:1);
        }
    }
    private static float wrap(float radians) { return (float)Math.atan2(Math.sin(radians),Math.cos(radians)); }
}
