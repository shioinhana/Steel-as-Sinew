package org.fsrpg.client;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/** A wrist carrier for Minecraft's unchanged THIRD_PERSON_RIGHT_HAND model transforms. */
public final class HeldItemTransform {
    private static final float DEG = (float) (Math.PI / 180);
    private static final Vector3f STANDARD_GRIP = standardDisplay().transformPosition(new Vector3f(2.5f / 16, 2.5f / 16, .5f));
    // The brown handle's centre in the actual vanilla sword sprite, above its pommel.
    private static final Vector3f DUAL_GRIP = standardDisplay().transformPosition(new Vector3f(3.5f / 16, 3.5f / 16, .5f));
    private HeldItemTransform() {}

    /** Vanilla handheld.json; used only to calibrate the carrier, never to replace a resource-pack transform. */
    public static Matrix4f standardDisplay() {
        return new Matrix4f().translation(0,.25f,.03125f).rotateXYZ(0,-90*DEG,55*DEG)
                .scale(.85f).translate(-.5f,-.5f,-.5f);
    }
    /** FS centimeters/Y-up become the humanoid layer's meters/Y-down at head-height origin. */
    public static Matrix4f palm(Matrix4f wristCm) {
        return new Matrix4f().translation(0,1.5f,0).scale(.01f,-.01f,-.01f)
                .mul(wristCm).translate(0,-6.25f,0).scale(100);
    }
    public static Matrix4f rightHand(Matrix4f wristCm) {
        return rapierCarrier(wristCm);
    }
    /** A fencing grip inclines the handle 50 degrees from wrist-to-knuckles. */
    public static Vector3f rapierBladeAxis() {
        return new Vector3f(0, -(float)Math.cos(50*DEG), (float)Math.sin(50*DEG));
    }
    public static Matrix4f rapierCarrier(Matrix4f wristCm) {
        return palm(wristCm).rotateX(120*DEG).rotateY(180*DEG)
                .translate(-DUAL_GRIP.x,-DUAL_GRIP.y,-DUAL_GRIP.z);
    }
    public static Matrix4f spearCarrier(Matrix4f wristCm) {
        // Official spear_in_hand is a diagonal generated model, 1.7x display scale.
        // Undo only its default display orientation, align the shaft to the authored palm axis.
        Matrix4f display=new Matrix4f().translation(0,2f/16,2f/16).rotateXYZ(5*DEG,270*DEG,-40*DEG)
                .scale(1.7f,1.7f,.85f).translate(-.5f,-.5f,-.5f);
        // In the official 32px texture the tip is upper-left: model-space shaft is (-X,+Y).
        return palm(wristCm).rotateX(-90*DEG).rotateZ(135*DEG).scale(1.7f,1.7f,.85f).translate(-.81f,-.19f,-.5f).mul(display.invert());
    }
    public static Matrix4f carrier(Matrix4f wristCm) {
        // The vanilla sword sprite runs diagonally (+X,+Y). With the official handheld display
        // transform this carrier puts that long axis exactly along the authored hand's -Y.
        return palm(wristCm).rotateX(170*DEG).rotateY(180*DEG)
                .translate(-STANDARD_GRIP.x,-STANDARD_GRIP.y,-STANDARD_GRIP.z);
    }
    /** A closed dual-blade grip holds the handle 50 degrees from wrist-to-knuckles. */
    public static Vector3f dualBladeAxis() {
        return new Vector3f(0, -(float)Math.cos(50*DEG), (float)Math.sin(50*DEG));
    }
    /** Blade face normal stays on hand +X; only the authored arm turns the cutting plane. */
    public static Matrix4f dualCarrier(Matrix4f wristCm) {
        return palm(wristCm).rotateX(120*DEG).rotateY(180*DEG)
                .translate(-DUAL_GRIP.x,-DUAL_GRIP.y,-DUAL_GRIP.z);
    }
    /** Original 0.1.1 item-space locator, before the official item display transform. */
    public static Matrix4f legacyLocator(Matrix4f locatorCm) {
        return new Matrix4f().translation(0,1.5f,0).scale(.01f,-.01f,-.01f)
                .mul(locatorCm).scale(-100,100,-100);
    }
    public static Matrix4f legacyRapierItem() {
        return new Matrix4f().rotateX(-90*DEG).scale(.85f)
                .translate(-.020905148f,.443653694f,0).rotateZ(41.987212496f*DEG);
    }
    public static Matrix4f legacyDualItem() {
        return new Matrix4f().rotateX(-90*DEG).translate(0,.125f,-.0625f);
    }
}
