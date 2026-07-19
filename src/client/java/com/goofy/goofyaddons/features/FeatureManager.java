package com.goofy.goofyaddons.features;

import com.goofy.goofyaddons.features.bookflipper.BazaarFlipper;

import java.util.ArrayList;
import java.util.List;

public class FeatureManager {
    List<Feature> featureList = new ArrayList<>();
    private Feature feature = null;

    public static final FeatureManager INSTANCE = new FeatureManager();

    private FeatureManager() {
        featureList.add(new BazaarFlipper());
    }

    public void onTick() {
        if (feature == null) return;
        feature.onTick();
    }

    public void start(String name) {
        feature = featureList.stream().filter(feature -> feature.name().equals(name)).findFirst().orElse(null);
        if (feature == null) return;
        feature.start();
    }

    public void stop() {
        if (feature == null) return;
        feature.stop();
        feature = null;
    }

    public void pause() {
        feature.pause();
    }

    public void resume() {
        feature.resume();
    }

}
