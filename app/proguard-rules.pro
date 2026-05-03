# AIDL interface used by RootService IPC
-keep class com.github.maxto3.pixelims.ICarrierConfigRootService { *; }
-keep class com.github.maxto3.pixelims.ICarrierConfigRootService$Stub { *; }

# Reflection — BuildConfig accessed via reflective log guard
-keep class com.github.maxto3.pixelims.BuildConfig { *; }
