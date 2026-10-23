package io.github.carlinklab;

import com.amap.api.navi.model.AMapLaneInfo;
import com.amap.api.navi.model.AMapNaviCross;
import java.util.LinkedHashSet;
import java.util.Set;

/** Main-thread state retained across a car video pause/reconnect, but never across navigation sessions. */
final class NavigationVisuals {
    AMapLaneInfo lanes;
    byte[] laneBackground, laneRecommended, modelCross;
    AMapNaviCross realCross;
    int laneEvents, realCrossEvents, modelCrossEvents, hideEvents;
    private final Set<Runnable> listeners = new LinkedHashSet<>();

    void observe(Runnable listener) { listeners.add(listener); listener.run(); }
    void remove(Runnable listener) { listeners.remove(listener); }
    void showLanes(AMapLaneInfo info) {
        if (info == null || info.laneCount <= 0) { hideLanes(); return; }
        lanes = info;
        laneBackground = laneRecommended = null;
        laneEvents++;
        changed();
    }
    void showLanes(byte[] background, byte[] recommended) {
        if (background == null || recommended == null || background.length == 0 || background.length != recommended.length) return;
        // Current SDK dispatches both overloads. Prefer the richer modern event when it is available.
        if (lanes != null) return;
        laneBackground = background.clone();
        laneRecommended = recommended.clone();
        laneEvents++;
        changed();
    }
    void hideLanes() {
        lanes = null;
        laneBackground = laneRecommended = null;
        hideEvents++;
        changed();
    }
    void showRealCross(AMapNaviCross cross) {
        if (cross == null || cross.getBitmap() == null || cross.getBitmap().isRecycled()) return;
        realCross = cross;
        modelCross = null;
        realCrossEvents++;
        changed();
    }
    void showModelCross(byte[] data) {
        if (data == null || data.length == 0) return;
        modelCross = data.clone();
        realCross = null;
        modelCrossEvents++;
        changed();
    }
    void hideRealCross() { realCross = null; hideEvents++; changed(); }
    void hideModelCross() { modelCross = null; hideEvents++; changed(); }
    void clear() {
        lanes = null;
        laneBackground = laneRecommended = modelCross = null;
        realCross = null;
        changed();
    }
    private void changed() {
        for (Runnable listener : listeners.toArray(new Runnable[0])) listener.run();
    }
    String diagnostics() {
        return "lane=" + laneEvents + ", realCross=" + realCrossEvents + ", modelCross=" + modelCrossEvents + ", hide=" + hideEvents;
    }
}
