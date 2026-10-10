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

        // 去掉 UA 里的 "; wv" 和 "Version/4.0"，尽量以普通 Chrome 身份请求，
        // 避免部分音源/后端对 WebView 做特殊处理
        String ua = settings.getUserAgentString();
        if (ua != null) {
            String patched = ua.replace("; wv", "").replace("Version/4.0 ", "");
            if (!patched.equals(ua)) {
                settings.setUserAgentString(patched);
            }
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
     * 注入到页面的监听脚本：把「正在播放哪首歌」回传给前台服务，
     * 用于刷新通知栏卡片（歌名 / 歌手 / 封面）与锁屏媒体会话。
     *
     * <p>取歌曲信息的优先级（多级兜底，站点改版也不容易失效）：
     * <ol>
     *   <li>{@code navigator.mediaSession.metadata} —— 站点自己设置的 title / artist / artwork；</li>
     *   <li>页面 DOM 的 {@code #nowName / #nowArtist / #nowCover} —— 播放器正在显示的歌曲信息；</li>
     * </ol>
     *
     * <p>上报分两路：
     * <ul>
     *   <li>{@code onState(播放中, 歌名, 歌手, 封面, 总时长)} —— 只在信息真正变化时上报（换歌 / 播放暂停），
     *       触发通知栏重建；</li>
     *   <li>{@code onTick(当前位置, 总时长)} —— 每秒上报，用于让 Android 10+ 媒体卡片的进度条走起来，
     *       不会重建通知，开销极小。</li>
     * </ul>
     */
    private static final String BRIDGE_JS =
            "(function(){"
                    + "if(window.__jhHooked)return;"
                    + "var a=document.getElementById('audio');"
                    + "if(!a){setTimeout(function(){window.__jhHooked=false;},1000);return;}"
                    + "window.__jhHooked=true;"
                    + "function txt(id){var e=document.getElementById(id);return e?String(e.textContent||'').trim():'';}"
                    + "function pick(){"
                    + "  var t='',ar='',cov='';"
                    + "  var md=(navigator.mediaSession&&navigator.mediaSession.metadata)||null;"
                    + "  if(md){"
                    + "    t=md.title||''; ar=md.artist||'';"
                    + "    if(md.artwork&&md.artwork.length){cov=md.artwork[md.artwork.length-1].src||'';}"
                    + "  }"
                    + "  if(!t)t=txt('nowName');"
                    + "  if(!ar)ar=txt('nowArtist');"
                    + "  if(!cov){var c=document.getElementById('nowCover');if(c&&c.getAttribute('src'))cov=c.src;}"
                    + "  if(t==='未在播放')t='';"
                    + "  if(ar==='—')ar='';"
                    + "  if(t==='解灰音乐'||t==='JieHuiMusic')t='';"
                    + "  return t+'\\u0001'+ar+'\\u0001'+cov;"
                    + "}"
                    + "var last='';"
                    + "function report(){"
                    + "  try{"
                    + "    var p=pick();"
                    + "    var d=(isFinite(a.duration)&&a.duration>0)?Math.round(a.duration*1000):0;"
                    + "    var k=(a.paused?'0':'1')+'|'+p+'|'+d;"
                    + "    if(k!==last){"
                    + "      last=k;"
                    + "      var q=p.split('\\u0001');"
                    + "      AndroidHost.onState(!a.paused,q[0]||'',q[1]||'',q[2]||'',d);"
                    + "    }"
                    + "    AndroidHost.onTick(Math.round((a.currentTime||0)*1000),d);"
                    + "  }catch(e){}"
                    + "}"
                    + "['play','pause','playing','ended','loadedmetadata','emptied','durationchange']"
                    + ".forEach(function(ev){a.addEventListener(ev,report);});"
                    + "setInterval(report,1000);"
                    + "report();"
                    + "})();";

    /**
     * 排障用浮层：长按页面 1.5 秒（或点通知栏「诊断」）在页面底部切出一块
     * 实时状态面板，直接显示 &lt;audio&gt; 的 paused / readyState / networkState /
     * error.code / 时长 / 音量 等，以及页面里的 JS 报错。
     * 播放异常时截个图就能定位，不用连 adb 抓 logcat。
     */
    private static final String DIAG_JS =
            "(function(){"
                    + "if(window.__jhDiagReady)return;window.__jhDiagReady=true;"
                    + "var box=null,timer=null,logs=[];"
                    + "function paint(){"
                    + "  if(!box)return;"
                    + "  var a=document.getElementById('audio'),s='';"
                    + "  if(a){"
                    + "    s+='paused='+a.paused+'   t='+a.currentTime.toFixed(2)+'/'+(isFinite(a.duration)?a.duration.toFixed(1):'Inf')+'\\n';"
                    + "    s+='readyState='+a.readyState+'  networkState='+a.networkState+'  error='+(a.error?a.error.code:'-')+'\\n';"
                    + "    s+='volume='+a.volume+'  muted='+a.muted+'  rate='+a.playbackRate+'\\n';"
                    + "    s+='src='+String(a.currentSrc||'-').replace(/^https?:\\/\\//,'').slice(0,62)+'\\n';"
                    + "  }else{s+='#audio 未找到\\n';}"
                    + "  s+='mediaSession='+('mediaSession' in navigator)+'\\n';"
                    + "  s+='UA='+navigator.userAgent.slice(-48)+'\\n';"
                    + "  if(logs.length)s+='--- 日志 ---\\n'+logs.join('\\n')+'\\n';"
                    + "  s+='【长按页面1.5秒 或 通知栏「诊断」关闭】';"
                    + "  box.textContent=s;"
                    + "}"
                    + "function add(m){"
                    + "  logs.push(new Date().toTimeString().slice(0,8)+' '+m);"
                    + "  if(logs.length>6)logs.shift();"
                    + "  paint();"
                    + "  try{AndroidHost.onDiag(m);}catch(e){}"
                    + "}"
                    + "window.addEventListener('error',function(e){add('ERR '+(e.message||'')+' @'+String(e.filename||'').split('/').pop()+':'+(e.lineno||''));});"
                    + "window.addEventListener('unhandledrejection',function(e){add('REJ '+((e.reason&&e.reason.message)||e.reason));});"
                    + "window.__jhToggleDiag=function(){"
                    + "  if(box){clearInterval(timer);timer=null;box.remove();box=null;return;}"
                    + "  box=document.createElement('div');"
                    + "  box.style.cssText='position:fixed;left:0;right:0;bottom:0;z-index:2147483647;"
                    + "background:rgba(0,0,0,.88);color:#66e28a;font:11px/1.5 monospace;padding:8px 10px;"
                    + "white-space:pre-wrap;word-break:break-all;max-height:55%;overflow:auto';"
                    + "  document.body.appendChild(box);"
                    + "  paint();timer=setInterval(paint,500);"
                    + "};"
                    + "var t0=0;"
                    + "document.addEventListener('touchstart',function(){t0=Date.now();},true);"
                    + "document.addEventListener('touchend',function(){if(Date.now()-t0>1500)window.__jhToggleDiag();},true);"
                    + "})();";

    private void injectBridge(WebView view) {
        view.evaluateJavascript(BRIDGE_JS + DIAG_JS, null);
    }

    /** 暴露给页面的原生接口 */
    public class NativeBridge {
        /** 歌曲级信息变化（换歌 / 播放暂停 / 封面变化），触发通知栏重建 */
        @JavascriptInterface
        public void onState(final boolean playing, final String title, final String artist,
                            final String cover, final int durationMs) {
            PlaybackService.updateState(playing, title, artist, cover, durationMs);
        }

        /** 每秒一次的位置上报，只用于刷新媒体会话进度，不重建通知 */
        @JavascriptInterface
        public void onTick(final int positionMs, final int durationMs) {
            PlaybackService.updateProgress(positionMs, durationMs);
        }

        @JavascriptInterface
        public void onDiag(String message) {
            android.util.Log.w("JieHuiMusic", "[page] " + message);
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
