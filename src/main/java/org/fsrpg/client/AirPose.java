package org.fsrpg.client;

/** Client presentation only: retain the last submitted stance in air, with explicit actions allowed to replace it. */
public final class AirPose {
    private AirPose() {}

    public static final class State<T> {
        private String key="";
        private T previous,takeoff;
        private boolean airborne,holding;

        public T sample(String presentationKey,boolean inAir,String phase,T current) {
            if(!presentationKey.equals(key)){key=presentationKey;previous=takeoff=null;airborne=false;}
            if(!inAir){airborne=false;holding=false;previous=current;takeoff=null;return current;}
            if(!airborne){airborne=true;if(previous==null)previous=current;takeoff=previous;}
            // These are actual authoritative actions, never a jump/fall substitution.
            boolean action=switch(phase){case "WINDUP","ACTIVE","RECOVERY","GUARDING","STUNNED","DODGING","DRAWING","SHEATHING"->true;default->false;};
            if(action){previous=current;if(!attack(phase)&&!phase.equals("GUARDING"))takeoff=current;}
            holding=!action;
            return previous;
        }
        public boolean holding(){return airborne&&holding;}
        public T takeoff(){return takeoff;}
        public void presented(T frame){previous=frame;}
    }
    public static boolean attack(String phase){return phase.equals("WINDUP")||phase.equals("ACTIVE")||phase.equals("RECOVERY");}
}
