package com.jiehui.music;

import android.webkit.WebView;

import java.lang.ref.WeakReference;

/**
 * WebView 的全局弱引用持有者。
 *
 * <p>作用：Activity 退到后台后，前台服务（通知栏按钮、锁屏控制、耳机线控）
 * 仍然需要向页面下发「播放 / 暂停 / 上一首 / 下一首」指令。
 * 服务与 Activity 之间没有一个天然的通道，这里用静态弱引用来搭桥，
 * 弱引用保证 Activity 真正销毁后 WebView 能被正常回收，不会内存泄漏。
 */
final class WebViewHolder {

    private static WeakReference<WebView> sRef = new WeakReference<>(null);

    private WebViewHolder() {
    }

    static void attach(WebView webView) {
        sRef = new WeakReference<>(webView);
    }

    static void detach() {
        sRef = new WeakReference<>(null);
    }

    static boolean isAlive() {
        return sRef.get() != null;
    }

    /**
     * 在主线程执行一段 JS（evaluateJavascript 必须在 UI 线程调用）。
     */
    static void eval(final String js) {
        final WebView webView = sRef.get();
        if (webView == null) {
            return;
        }
        webView.post(new Runnable() {
            @Override
            public void run() {
                try {
                    webView.evaluateJavascript(js, null);
                } catch (Throwable ignored) {
                    // WebView 正在销毁等边界情况，忽略即可
                }
            }
        });
    }
}
