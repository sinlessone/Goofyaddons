package com.goofy.goofyaddons.keybinds;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

public class GoofyKeybinds {

    private static final String CATEGORY = "key.categories.goofyaddons";

    public static KeyMapping startKey;
    public static KeyMapping stopKey;

    public static void register() {
        startKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.goofyaddons.start",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_J,
                CATEGORY
        ));

        stopKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.goofyaddons.stop",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_K,
                CATEGORY
        ));
    }
}
