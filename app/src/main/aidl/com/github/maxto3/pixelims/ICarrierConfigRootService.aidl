package com.github.maxto3.pixelims;

import android.os.PersistableBundle;

interface ICarrierConfigRootService {
    int getImsProvisioningInt(int subId, int key);
    void setImsProvisioningInt(int subId, int key, int value);
    boolean overrideCarrierConfig(int subId, in PersistableBundle overrides, boolean persistent);
    PersistableBundle getCarrierConfig(int subId);
    void resetIms(int subId);
    List<String> getActiveSubscriptions();
    boolean isImsRegistered(int subId);
    int getWfcMode();
    boolean setWfcMode(int mode);
    boolean sanitizeApnImsType();
    String getCaptivePortalConfig();
    boolean applyCaptivePortalCnUrls();
    boolean restoreCaptivePortalDefaultUrls();
    String captureLogcat(int lines);
    String queryApnList();
    boolean updateApnType(int id, String type);
    boolean healImsApnType(String mcc, String mnc);
    boolean healImsApnTypeById(int subId);
    boolean ensureAdvancedCalling(int subId);
}
