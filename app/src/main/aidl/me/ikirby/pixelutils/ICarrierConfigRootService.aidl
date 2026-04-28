package me.ikirby.pixelutils;

import android.os.PersistableBundle;

interface ICarrierConfigRootService {
    int getImsProvisioningInt(int subId, int key);
    void setImsProvisioningInt(int subId, int key, int value);
    boolean overrideCarrierConfig(int subId, in PersistableBundle overrides, boolean persistent);
    PersistableBundle getCarrierConfig(int subId);
    void resetIms(int subId);
    List<String> getActiveSubscriptions();
}
