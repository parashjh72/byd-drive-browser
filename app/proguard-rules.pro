# Keep the JavaScript bridge used by media.js
-keepclassmembers class com.bydbrowser.app.MainActivity$MediaBridge {
    @android.webkit.JavascriptInterface <methods>;
}
