# Keep JavaScript interface methods
-keepclassmembers class com.sakura.player.MainActivity$WebAppInterface {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep OkHttp
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }

# Keep Jsoup
-keep class org.jsoup.** { *; }

# Keep Room entities
-keep class com.sakura.player.data.** { *; }

# Keep data classes
-keep class com.sakura.player.scraper.** { *; }
-keep class com.sakura.player.download.** { *; }
-keep class com.sakura.player.follow.** { *; }
