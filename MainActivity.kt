package com.zenfocus.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

class MainActivity : Activity() {
    private lateinit var web: WebView
    private var keepScreenOnEnabled = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request Notification permission on Android 13+ (API 33+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        applyScreenAwake(true)
        window.statusBarColor = android.graphics.Color.rgb(7, 8, 12)
        window.navigationBarColor = android.graphics.Color.rgb(7, 8, 12)

        web = WebView(this)
        web.setBackgroundColor(android.graphics.Color.rgb(7, 8, 12))
        web.webChromeClient = WebChromeClient()

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
        }

        // Expose native Android bridge to your HTML/JS
        web.addJavascriptInterface(ZenAndroidBridge(), "ZenAndroid")

        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectLockScreenAndAwakeHelper()
            }
        }

        setContentView(web)
        web.loadUrl("file:///android_asset/index.html")
    }

    private fun applyScreenAwake(enable: Boolean) {
        keepScreenOnEnabled = enable
        runOnUiThread {
            if (enable) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    // Automatically monitors your existing HTML timer & injects a Screen Awake toggle button
    private fun injectLockScreenAndAwakeHelper() {
        val js = """
            (function() {
                if (window.__zenAndroidInjected) return;
                window.__zenAndroidInjected = true;

                // 1. Add floating Screen Awake toggle chip in bottom-left corner
                const awakeBtn = document.createElement('button');
                let isAwake = true;
                awakeBtn.innerHTML = '☀️ Screen Awake: ON';
                awakeBtn.style.cssText = 'position:fixed;bottom:14px;left:14px;z-index:99999;background:rgba(15,18,28,0.92);color:#f8fafc;border:1px solid rgba(255,255,255,0.18);border-radius:999px;padding:6px 12px;font-size:11px;font-weight:600;backdrop-filter:blur(8px);box-shadow:0 4px 12px rgba(0,0,0,0.4);cursor:pointer;';
                awakeBtn.onclick = function() {
                    isAwake = !isAwake;
                    awakeBtn.innerHTML = isAwake ? '☀️ Screen Awake: ON' : '🌙 Screen Awake: OFF';
                    awakeBtn.style.borderColor = isAwake ? 'rgba(239,68,68,0.6)' : 'rgba(255,255,255,0.18)';
                    if (window.ZenAndroid) {
                        window.ZenAndroid.setScreenAwake(isAwake);
                    }
                };
                document.body.appendChild(awakeBtn);

                // 2. Sync live timer text to Android Lock Screen Notification every second
                let lastTimerVal = '';
                setInterval(function() {
                    try {
                        const bodyText = document.body.innerText || '';
                        // Match HH:MM:SS or MM:SS timer readout on page
                        const timeMatch = bodyText.match(/\b(\d{1,2}:\d{2}(?::\d{2})?)\b/);
                        const currentTime = timeMatch ? timeMatch[1] : '';

                        // Detect active mode (Focus / Short Break / Long Break)
                        let mode = 'ZenFocus Ultra';
                        if (/Short Break/i.test(bodyText) && document.querySelector('.active')?.innerText?.match(/Short/i)) {
                            mode = 'ZenFocus • Short Break';
                        } else if (/Long Break/i.test(bodyText) && document.querySelector('.active')?.innerText?.match(/Long/i)) {
                            mode = 'ZenFocus • Long Break';
                        } else {
                            mode = 'ZenFocus • Deep Focus';
                        }

                        // Check if timer is actively counting down (value changed)
                        if (currentTime && currentTime !== lastTimerVal) {
                            lastTimerVal = currentTime;
                            if (window.ZenAndroid) {
                                window.ZenAndroid.updateTimerNotification(mode, '⏳ Time Remaining: ' + currentTime);
                            }
                        }
                    } catch (e) {}
                }, 1000);
            })();
        """.trimIndent()
        web.evaluateJavascript(js, null)
    }

    inner class ZenAndroidBridge {
        @JavascriptInterface
        fun setScreenAwake(enable: Boolean) {
            applyScreenAwake(enable)
        }

        @JavascriptInterface
        fun updateTimerNotification(title: String, statusText: String) {
            val serviceIntent = Intent(this@MainActivity, FocusTimerService::class.java).apply {
                action = FocusTimerService.ACTION_UPDATE
                putExtra(FocusTimerService.EXTRA_TITLE, title)
                putExtra(FocusTimerService.EXTRA_TEXT, statusText)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        }

        @JavascriptInterface
        fun stopTimerNotification() {
            val serviceIntent = Intent(this@MainActivity, FocusTimerService::class.java).apply {
                action = FocusTimerService.ACTION_STOP
            }
            startService(serviceIntent)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::web.isInitialized && web.canGoBack()) {
            web.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
