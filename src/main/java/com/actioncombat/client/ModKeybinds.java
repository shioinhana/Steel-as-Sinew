package com.actioncombat.client;

import com.actioncombat.ActionCombat;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * All bindings are regular KeyMappings, so players can rebind them in vanilla
 * controls. Attack and use stay on the vanilla mouse bindings (they become the
 * left/right swings while drawn); block and inspect get their own keys, defaulted
 * to keys vanilla leaves free.
 */
public final class ModKeybinds {
    public static final KeyMapping.Category CATEGORY_COMBAT =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath(ActionCombat.MODID, "combat"));

    /** R: draw / sheathe. Drawn = mod combat mode, sheathed = untouched vanilla. */
    public static final KeyMapping DRAW = new KeyMapping(
            "key.actioncombat.draw",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_R,
            CATEGORY_COMBAT
    );

    /** Guard, held. Moved off the use key because that now drives the right swing. */
    public static final KeyMapping BLOCK = new KeyMapping(
            "key.actioncombat.block",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_X,
            CATEGORY_COMBAT
    );

    /** Weapon inspect, on demand (the idle auto-inspect still exists). */
    public static final KeyMapping INSPECT = new KeyMapping(
            "key.actioncombat.inspect",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_V,
            CATEGORY_COMBAT
    );

    /** Drawn combat crouch. Vanilla sneak (Shift) becomes dodge while drawn. */
    public static final KeyMapping CROUCH = new KeyMapping(
            "key.actioncombat.crouch",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_C,
            CATEGORY_COMBAT
    );

    public static final KeyMapping CAM_FORWARD = new KeyMapping(
            "key.actioncombat.cam_forward",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_I,
            CATEGORY_COMBAT
    );
    public static final KeyMapping CAM_LEFT = new KeyMapping(
            "key.actioncombat.cam_left",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_J,
            CATEGORY_COMBAT
    );
    public static final KeyMapping CAM_BACK = new KeyMapping(
            "key.actioncombat.cam_back",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_K,
            CATEGORY_COMBAT
    );
    public static final KeyMapping CAM_RIGHT = new KeyMapping(
            "key.actioncombat.cam_right",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_L,
            CATEGORY_COMBAT
    );

    /** Q: rapier lock-on — switch to the enemy left of the current target. */
    public static final KeyMapping LOCK_PREV = new KeyMapping(
            "key.actioncombat.lock_prev",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_Q,
            CATEGORY_COMBAT
    );

    /** E: rapier lock-on — switch to the enemy right of the current target. */
    public static final KeyMapping LOCK_NEXT = new KeyMapping(
            "key.actioncombat.lock_next",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_E,
            CATEGORY_COMBAT
    );

    private ModKeybinds() {}
}
