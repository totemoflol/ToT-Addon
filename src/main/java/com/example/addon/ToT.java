package com.tot.addon;

import com.tot.addon.modules.SpawnerSaver;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;

public class ToT extends MeteorAddon {
    public static final String MOD_ID = "tot";
    public static final Category CATEGORY = Categories.ToT;

    @Override
    public void onInitialize() {
        // Register the custom category
        Modules.get().add(new SpawnerSaver());
    }

    @Override
    public String getPackage() {
        return "com.tot.addon";
    }
}
