package com.jiehui.music;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

/**
 * 唯一的 Activity：一个全屏 WebView 承载 yy.lys.cn.eu.org。
 *
 * <p>「后台播放几分钟自动暂停」的三个可能原因，这里逐一堵掉：
 * <ol>
 *   <li><b>系统冻结进程</b> —— 交给 {@link PlaybackService} 媒体前台服务解决；</li>
 *   <li><b>WebView 被主动暂停</b> —— onPause() 里刻意不调用 webView.onPause()；</li>
 *   <li><b>返回键把 Activity 销毁</b> —— 返回键改为 moveTaskToBack()，Activity 与 WebView 继续存活。</li>
 * </ol>
 */
public class MainActivity extends Activity {

    /** 站点地址 */
    private static final String START_URL = "https://yy.lys.cn.eu.org/";
    /** 站内域名，其他域名跳系统浏览器 */
    private static final String HOME_HOST = "yy.lys.cn.eu.org";

    private static final String PREFS = "jiehui_prefs";
    private static final String KEY_BATTERY_ASKED = "battery_asked";
    private static final String KEY_BACK_TOASTED = "back_toasted";

    private WebView webView;

    // =====================================================================
    // 生命周期
    // =====================================================================

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);
        configureWebView();

        // 让前台服务随时能向页面下发播放控制指令
        WebViewHolder.attach(webView);
        webView.addJavascriptInterface(new NativeBridge(), "AndroidHost");

        webView.loadUrl(START_URL);

        // 关键一步：拉起媒体前台服务（必须在 Activity 前台时调用）
        PlaybackService.start(this);

        requestNotificationPermission();
        maybeAskIgnoreBatteryOptimization();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
            webView.resumeTimers();
        }
        // 回到前台时补一次保活（部分 ROM 会回收前台服务）
        PlaybackService.start(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // ⚠️ 这里刻意不调用 webView.onPause()。
        // webView.onPause() 会暂停所有 JS 定时器与媒体播放，是后台断播的常见元凶。
        // 保活完全交给 PlaybackService 的前台通知。
    }

    @Override
    protected void onDestroy() {
        WebViewHolder.detach();
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    /** 返回键：退到后台继续播放，而不是销毁 Activity */
    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        moveTaskToBack(true);
        toastOnce(KEY_BACK_TOASTED, getString(R.string.back_hint));
    }

    /** 耳机线控 / 蓝牙按键 */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
                WebViewHolder.eval(PlaybackService.JS_TOGGLE);
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                WebViewHolder.eval(PlaybackService.JS_PLAY);
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_STOP:
                WebViewHolder.eval(PlaybackService.JS_PAUSE);
                return true;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                WebViewHolder.eval(PlaybackService.JS_NEXT);
                return true;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                WebViewHolder.eval(PlaybackService.JS_PREV);
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    // =====================================================================
    // WebView 配置
    // =====================================================================

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);          // 歌单 / 登录态存 localStorage
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false); // 允许脚本自动续播下一首
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        // 音源直链常见 http 地址，必须放开混合内容，否则播放失败
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        // 去掉 UA 里的 "; wv" 标记，尽量以普通 Chrome 身份请求，兼容性更好
        String ua = settings.getUserAgentString();
        if (ua != null && ua.contains("; wv")) {
            settings.setUserAgentString(ua.replace("; wv", ""));
        }

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        webView.setBackgroundColor(0xFF0F1115);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setHorizontalScrollBarEnabled(false);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUrl(request.getUrl().toString());
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrl(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                injectBridge(view);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                // 只对本站授权，其它来源一律拒绝
                String origin = request.getOrigin() != null ? request.getOrigin().toString() : "";
                if (origin.contains(HOME_HOST)) {
                    request.grant(request.getResources());
                } else {
                    request.deny();
                }
            }

            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                // 保持静默，页面自带 loading 状态
            }
        });
    }

    /** 站内链接留在 WebView，站外链接交给系统浏览器 */
    private boolean handleUrl(String url) {
        if (url == null) {
            return false;
        }
        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme();
        if (scheme != null && !scheme.startsWith("http")) {
            return false; // tel: / weixin: 等交给系统默认处理
        }
        String host = uri.getHost();
        if (host != null && (host.equals(HOME_HOST) || host.endsWith("." + HOME_HOST))) {
            return false;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Throwable ignored) {
        }
        return true;
    }

    // =====================================================================
    // 页面 <-> 原生 桥
    // =====================================================================

    /**
     * 注入到页面的监听脚本：页面里的 <audio> 一旦播放 / 暂停 / 换曲，
     * 就通过 AndroidHost 把状态回传给前台服务，用于刷新通知栏和媒体会话。
     */
    private static final String BRIDGE_JS =
            "(function(){"
                    + "if(window.__jhHooked)return;"
                    + "var a=document.getElementById('audio');"
                    + "if(!a){setTimeout(function(){window.__jhHooked=false;},1000);return;}"
                    + "window.__jhHooked=true;"
                    + "function report(){"
                    + "  try{"
                    + "    var md=(navigator.mediaSession&&navigator.mediaSession.metadata)||null;"
                    + "    AndroidHost.onState(!a.paused, md?(md.title||''):'', md?(md.artist||''):'');"
                    + "  }catch(e){}"
                    + "}"
                    + "['play','pause','playing','ended','loadedmetadata','emptied'].forEach(function(ev){a.addEventListener(ev,report);});"
                    + "setInterval(report,1500);"
                    + "report();"
                    + "})();";

    private void injectBridge(WebView view) {
        view.evaluateJavascript(BRIDGE_JS, null);
    }

    /** 暴露给页面的原生接口 */
    public class NativeBridge {
        @JavascriptInterface
        public void onState(final boolean playing, final String title, final String artist) {
            PlaybackService.updateState(playing, title, artist);
        }
    }

    // =====================================================================
    // 权限与引导
    // =====================================================================

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1001);
        }
    }

    /**
     * 国产 ROM（MIUI / EMUI / ColorOS / OriginOS…）对后台管控极严，
     * 只靠前台服务也可能被清掉。首次启动引导用户加入电池优化白名单。
     */
    private void maybeAskIgnoreBatteryOptimization() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null || pm.isIgnoringBatteryOptimizations(getPackageName())) {
            return;
        }
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (sp.getBoolean(KEY_BATTERY_ASKED, false)) {
            return;
        }
        sp.edit().putBoolean(KEY_BATTERY_ASKED, true).apply();

        new AlertDialog.Builder(this)
                .setTitle(R.string.battery_title)
                .setMessage(R.string.battery_message)
                .setCancelable(false)
                .setPositiveButton(R.string.battery_ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        openBatteryOptimizationSettings();
                    }
                })
                .setNegativeButton(R.string.battery_later, null)
                .show();
    }

    private void openBatteryOptimizationSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Throwable t1) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Throwable ignored) {
            }
        }
    }

    private void toastOnce(String key, String message) {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (sp.getBoolean(key, false)) {
            return;
        }
        sp.edit().putBoolean(key, true).apply();
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}
