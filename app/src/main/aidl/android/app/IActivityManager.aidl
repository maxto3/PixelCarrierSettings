package android.app;

interface IActivityManager {
    void startDelegateShellPermissionIdentity(int delegatePid, in String[] permissions);
    void stopDelegateShellPermissionIdentity();
}