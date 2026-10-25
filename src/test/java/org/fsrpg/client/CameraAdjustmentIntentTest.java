package org.fsrpg.client;

/** Ctrl chords, native-input ownership and independent saved profile composition. */
public final class CameraAdjustmentIntentTest {
    private static int checks;
    public static void main(String[] args) {
        chordsAndNativeKeys(); axesAndLimits();
        System.out.println("CameraAdjustmentIntentTest passed: " + checks + " checks");
    }
    private static void chordsAndNativeKeys() {
        var intent = new CameraAdjustmentIntent();
        check(!intent.sample(true,false,true,false,false,false).adjusting(),"I alone does not adjust or suppress sprint");
        check(!intent.sample(true,true,false,false,false,false).adjusting(),"Ctrl alone retains native sprint");
        check(!intent.sample(true,false,false,true,true,false).adjusting(),"J/K without Ctrl retain journal/editor input");
        var chord=intent.sample(true,true,true,false,false,false);
        check(chord.adjusting(),"Ctrl plus I adjusts immediately"); equal(0,chord.horizontal()); equal(1,chord.vertical());
        check(!intent.sample(true,true,false,false,false,false).adjusting(),"Releasing the direction returns Ctrl to native sprint");
        check(intent.sample(true,true,false,false,true,false).adjusting(),"Direction after an already held Ctrl starts immediately");
        check(!intent.sample(false,true,true,false,false,false).adjusting(),"GUI, first person and focus loss disable adjustments");
        check(!intent.sample(true,false,true,false,false,false).adjusting(),"Releasing Ctrl stops a held direction");
        var shift=new ShiftBurstIntent(); shift.sample(false,true,false,true,0,0,1);
        check(shift.sample(true,true,false,true,1,0,1).startsWith("down "),"Independent Shift burst retains its first-tick response");
        check(shift.sample(false,true,false,true,2,0,1).equals("up"),"Independent Shift release retains its original response");
    }
    private static void axesAndLimits() {
        var intent=new CameraAdjustmentIntent();
        var diagonal=intent.sample(true,true,true,false,false,true);
        equal(1,Math.hypot(diagonal.horizontal(),diagonal.vertical()));
        var cancel=intent.sample(true,true,true,true,true,true);
        check(cancel.adjusting(),"Opposed keys still own journal/editor conflicts"); equal(0,cancel.horizontal()); equal(0,cancel.vertical());
        var ordinary=new CameraProfilePosition(-.27,.46,4.1); var mounted=ordinary.forMounted(1.2);
        equal(-.27,mounted.side()); equal(.46,mounted.height()); equal(5.3,mounted.distance());
        var moved=mounted.adjust(1,-1,.025,true);
        equal(-.245,moved.side()); equal(.435,moved.height()); equal(5.3,moved.distance());
        equal(-.27,ordinary.side()); equal(.46,ordinary.height()); equal(4.1,ordinary.distance());
        equal(1,new CameraProfilePosition(0,.99,3).adjust(0,1,.1,false).height());
        equal(1.5,new CameraProfilePosition(1.49,1.49,4.2).adjust(1,1,.1,true).height());
        equal(1.5,new CameraProfilePosition(1.49,1.49,4.2).adjust(1,1,.1,true).side());
        equal(-.5,new CameraProfilePosition(-1.49,-.49,4.2).adjust(-1,-1,.1,true).height());
        equal(-1.5,new CameraProfilePosition(-1.49,-.49,4.2).adjust(-1,-1,.1,true).side());
        equal(8,new CameraProfilePosition(0,0,6).forMounted(3).distance());
        for(double invalid:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY}){
            boolean rejected=false; try{ordinary.adjust(invalid,0,.025,false);}catch(IllegalArgumentException expected){rejected=true;}
            check(rejected,"Non-finite adjustment rejected");
        }
    }
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static void equal(double expected,double actual){check(Double.isFinite(actual)&&Math.abs(expected-actual)<1e-9,expected+" != "+actual);}
}
