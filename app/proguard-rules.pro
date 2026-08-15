# The Watch Face Format package names this service as a string in its own XML, so R8 cannot see
# the reference and must be told to leave the name alone.
-keep class com.awakeface.watch.AwakeComplicationService { *; }
-keep class com.awakeface.watch.AwakeListenerService { *; }
-keep class com.awakeface.watch.AwakeWatchFaceService { *; }
