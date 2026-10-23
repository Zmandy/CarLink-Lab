package io.github.carlinklab;

/** No-op adapter for the pinned AMap navigation listener API. */
abstract class NaviListenerAdapter implements com.amap.api.navi.AMapNaviListener {
    @Override public void onInitNaviFailure() {}
    @Override public void onInitNaviSuccess() {}
    @Override public void onStartNavi(int arg0) {}
    @Override public void onTrafficStatusUpdate() {}
    @Override public void onLocationChange(com.amap.api.navi.model.AMapNaviLocation arg0) {}
    @Override public void onGetNavigationText(int arg0, java.lang.String arg1) {}
    @Override public void onGetNavigationText(java.lang.String arg0) {}
    @Override public void onEndEmulatorNavi() {}
    @Override public void onArriveDestination() {}
    @Override public void onCalculateRouteFailure(int arg0) {}
    @Override public void onReCalculateRouteForYaw() {}
    @Override public void onReCalculateRouteForTrafficJam() {}
    @Override public void onArrivedWayPoint(int arg0) {}
    @Override public void onGpsOpenStatus(boolean arg0) {}
    @Override public void onNaviInfoUpdate(com.amap.api.navi.model.NaviInfo arg0) {}
    @Override public void updateCameraInfo(com.amap.api.navi.model.AMapNaviCameraInfo[] arg0) {}
    @Override public void updateIntervalCameraInfo(com.amap.api.navi.model.AMapNaviCameraInfo arg0, com.amap.api.navi.model.AMapNaviCameraInfo arg1, int arg2) {}
    @Override public void onServiceAreaUpdate(com.amap.api.navi.model.AMapServiceAreaInfo[] arg0) {}
    @Override public void showCross(com.amap.api.navi.model.AMapNaviCross arg0) {}
    @Override public void hideCross() {}
    @Override public void showModeCross(com.amap.api.navi.model.AMapModelCross arg0) {}
    @Override public void hideModeCross() {}
    @Override public void showLaneInfo(com.amap.api.navi.model.AMapLaneInfo[] arg0, byte[] arg1, byte[] arg2) {}
    @Override public void showLaneInfo(com.amap.api.navi.model.AMapLaneInfo arg0) {}
    @Override public void hideLaneInfo() {}
    @Override public void onCalculateRouteSuccess(int[] arg0) {}
    @Override public void notifyParallelRoad(int arg0) {}
    @Override public void OnUpdateTrafficFacility(com.amap.api.navi.model.AMapNaviTrafficFacilityInfo[] arg0) {}
    @Override public void OnUpdateTrafficFacility(com.amap.api.navi.model.AMapNaviTrafficFacilityInfo arg0) {}
    @Override public void updateAimlessModeStatistics(com.amap.api.navi.model.AimLessModeStat arg0) {}
    @Override public void updateAimlessModeCongestionInfo(com.amap.api.navi.model.AimLessModeCongestionInfo arg0) {}
    @Override public void onPlayRing(int arg0) {}
    @Override public void onCalculateRouteSuccess(com.amap.api.navi.model.AMapCalcRouteResult arg0) {}
    @Override public void onCalculateRouteFailure(com.amap.api.navi.model.AMapCalcRouteResult arg0) {}
    @Override public void onNaviRouteNotify(com.amap.api.navi.model.AMapNaviRouteNotifyData arg0) {}
    @Override public void onGpsSignalWeak(boolean arg0) {}
}
