# The native compositor calls these by name (JNI GetMethodID): keep them from R8 renaming.
-keep interface io.pepe.launcher.compositor.Compositor$Listener { *; }
-keepclassmembers class * implements io.pepe.launcher.compositor.Compositor$Listener {
    public void onWindowAdded(int, int, java.lang.String, java.lang.String);
    public void onWindowChanged(int, java.lang.String, java.lang.String);
    public void onWindowRemoved(int);
    public void onClipboard(java.lang.String);
    public void onTextInput(int, boolean);
}
