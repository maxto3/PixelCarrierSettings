package com.android.internal.telephony;

import android.os.PersistableBundle;

interface ITelephony {
    int getImsProvisioningInt(int subId, int key);
    void setImsProvisioningInt(int subId, int key, int value);
    void resetIms(int slotIndex);
    void overrideCarrierConfig(int subId, in PersistableBundle overrides, boolean persistent);
}