package com.android.internal.telephony;

import android.os.PersistableBundle;

interface ICarrierConfigLoader {
    PersistableBundle getConfigForSubId(int subId, String callingPackage, String callingFeatureId);
    void overrideConfig(int subId, in PersistableBundle overrides, boolean persistent);
}