package org.fsrpg.client;

/** WASD keeps its camera-space world direction while the fighter faces a lock. */
public final class OrbitMovementTest {
    public static void main(String[] args){
        int checks=0;
        for(int camera=-180;camera<=180;camera+=15)for(int actor=-180;actor<=180;actor+=15)
            for(float[] input:new float[][]{{0,1},{0,-1},{1,0},{-1,0},{.70710677f,.70710677f},{0,0}}){
                var local=OrbitMovement.relative(input[0],input[1],camera,actor);
                double a=Math.toRadians(actor),c=Math.toRadians(camera);
                double actualX=local.left()*Math.cos(a)-local.forward()*Math.sin(a);
                double actualZ=local.forward()*Math.cos(a)+local.left()*Math.sin(a);
                double expectedX=input[0]*Math.cos(c)-input[1]*Math.sin(c);
                double expectedZ=input[1]*Math.cos(c)+input[0]*Math.sin(c);
                if(Math.hypot(actualX-expectedX,actualZ-expectedZ)>1e-6)throw new AssertionError("Camera/actor basis mismatch");
                if(Math.abs(Math.hypot(local.left(),local.forward())-Math.hypot(input[0],input[1]))>1e-6)throw new AssertionError("Movement magnitude changed");
                checks+=2;
            }
        System.out.println("OrbitMovementTest passed: "+checks+" camera/actor direction and magnitude checks");
    }
}
