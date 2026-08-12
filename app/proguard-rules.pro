# WebView 通过名称调用 @JavascriptInterface 方法；保留接口成员，其他代码仍可正常混淆。
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
