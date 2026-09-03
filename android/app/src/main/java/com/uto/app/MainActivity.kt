package com.uto.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "UtoMainActivity"
    }

    private lateinit var webView: WebView
    private lateinit var loadingOverlay: View
    private lateinit var statusText: TextView
    private lateinit var statusDetail: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var percentText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        loadingOverlay = findViewById(R.id.loadingOverlay)
        statusText = findViewById(R.id.statusText)
        statusDetail = findViewById(R.id.statusDetail)
        progressBar = findViewById(R.id.progressBar)
        percentText = findViewById(R.id.percentText)

        requestBatteryOptimizationExemption()
        setupWebView()
        startSetupFlow()
    }

    override fun onDestroy() {
        super.onDestroy()
        CodexServerManager(this).stopServer()
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        try {
            @Suppress("BatteryLife")
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Battery optimization exemption: ${e.message}")
        }
    }

    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            setSupportZoom(false)
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        webView.webViewClient = WebViewClient()
    }

    private fun startSetupFlow() {
        progressBar.isIndeterminate = false
        progressBar.progress = 0
        updateProgress("Preparing Uto…", 0)

        Thread {
            try {
                runSetup()
            } catch (e: Exception) {
                Log.e(TAG, "Setup failed", e)
                // Still launch the chat UI — it works without server
                launchChatUi()
            }
        }.start()
    }

    private fun updateProgress(status: String, percent: Int, detail: String? = null) {
        runOnUiThread {
            statusText.text = status
            progressBar.progress = percent
            percentText.text = "$percent%"
            if (detail != null) {
                statusDetail.text = detail
                statusDetail.visibility = View.VISIBLE
            } else {
                statusDetail.visibility = View.GONE
            }
        }
    }

    private fun runSetup() {
        // Step 1: Extract bootstrap (0-60%)
        if (!BootstrapInstaller.isBootstrapInstalled(this)) {
            updateProgress("Extracting environment…", 2, "This may take a moment on first run")
            BootstrapInstaller.install(this) { msg ->
                runOnUiThread {
                    statusDetail.text = msg
                    statusDetail.visibility = View.VISIBLE
                }
            }
        }
        updateProgress("Environment extracted", 60)

        // At this point the chat UI is ready — show it!
        // The rest is optional background setup
        launchChatUi()

        // Background setup: proot, node, codex (non-blocking)
        Thread {
            try {
                backgroundSetup()
            } catch (e: Exception) {
                Log.w(TAG, "Background setup partial failure: ${e.message}")
            }
        }.start()
    }

    private fun backgroundSetup() {
        val serverManager = CodexServerManager(this)

        // Install proot
        if (!serverManager.isProotInstalled()) {
            serverManager.installProot { }
        }

        // Install Node.js
        if (!serverManager.isNodeInstalled()) {
            serverManager.installNode { }
        }

        // Install Codex CLI
        if (!serverManager.isCodexInstalled()) {
            serverManager.installCodex { }
        }

        // Workspace
        serverManager.ensureDefaultWorkspace()
        serverManager.ensureFullAccessConfig()

        Log.i(TAG, "Background setup complete")
    }

    private fun launchChatUi() {
        runOnUiThread {
            if (loadingOverlay.visibility == View.VISIBLE) {
                updateProgress("Ready!", 100)
                loadingOverlay.postDelayed({
                    loadingOverlay.visibility = View.GONE
                    webView.visibility = View.VISIBLE
                    webView.loadUrl("file:///android_asset/web/index.html")
                }, 200)
            }
        }
    }

    @Deprecated("Use onBackPressedDispatcher")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }
}
