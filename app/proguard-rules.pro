# 默认不混淆（minifyEnabled=false）。若将来开启混淆，需要保留 JS 桥接方法：
-keepclassmembers class com.jiehui.music.MainActivity$NativeBridge {
    public *;
}
-keepattributes JavascriptInterface
-keep class * implements android.webkit.JavascriptInterface { *; }
