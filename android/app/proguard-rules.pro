# Native bindings use callbacks and reflection; retain them if shrinking is enabled later.
-keep class xyz.moondrop.nativebridge.** { *; }
-keep class go.** { *; }
