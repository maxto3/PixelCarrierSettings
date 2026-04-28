package com.android.internal.telephony;

interface ISub {
    int getSlotIndex(int subId);
    List getActiveSubscriptionInfoList(String callingPackage, String feature, boolean isSubInfoDeprioritized);
}