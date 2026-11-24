package com.example.durakoverlay

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var prefs: Prefs
    private lateinit var parser: GameParser
    private lateinit var btnOverlay: Button

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = Prefs(this)
        parser = GameParser { prefs.deckSize }

        web = findViewById(R.id.web)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
        }
        web.addJavascriptInterface(WebViewBridge(parser), WebViewBridge.NAME)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(web, WebViewBridge.INJECT_JS, setOf("*"))
        }
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            // Запасной вариант, если DOCUMENT_START_SCRIPT не поддерживается.
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                view.evaluateJavascript(WebViewBridge.INJECT_JS, null)
            }
        }
        if (savedInstanceState == null) web.loadUrl(START_URL)

        btnOverlay = findViewById(R.id.btnOverlay)
        btnOverlay.setOnClickListener { toggleOverlay() }
        findViewById<Button>(R.id.btnSettings).setOnClickListener { showSettings() }
        findViewById<Button>(R.id.btnReset).setOnClickListener { GameStore.publish(GameState()) }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        updateOverlayButton()
    }

    private fun updateOverlayButton() {
        btnOverlay.text = if (OverlayService.running) "Оверлей: выкл" else "Оверлей: вкл"
    }

    private fun toggleOverlay() {
        val intent = Intent(this, OverlayService::class.java)
        if (OverlayService.running) {
            stopService(intent)
            OverlayService.running = false
        } else {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Разрешите показ поверх других окон", Toast.LENGTH_LONG).show()
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
                return
            }
            ContextCompat.startForegroundService(this, intent)
            OverlayService.running = true
        }
        updateOverlayButton()
    }

    private fun showSettings() {
        val v = layoutInflater.inflate(R.layout.settings_dialog, null)
        val font = v.findViewById<SeekBar>(R.id.seekFont)
        val alpha = v.findViewById<SeekBar>(R.id.seekAlpha)
        val rg = v.findViewById<RadioGroup>(R.id.rgDeck)

        font.progress = prefs.fontSp - 10
        alpha.progress = prefs.alphaPct - 20
        rg.check(
            when (prefs.deckSize) {
                24 -> R.id.rb24
                52 -> R.id.rb52
                else -> R.id.rb36
            }
        )

        font.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) prefs.fontSp = p + 10
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        alpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) prefs.alphaPct = p + 20
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        rg.setOnCheckedChangeListener { _, id ->
            prefs.deckSize = when (id) {
                R.id.rb24 -> 24
                R.id.rb52 -> 52
                else -> 36
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Настройки")
            .setView(v)
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onDestroy() {
        web.removeJavascriptInterface(WebViewBridge.NAME)
        super.onDestroy()
    }

    companion object {
        // Проверьте актуальный адрес браузерной версии игры.
        const val START_URL = "https://durak.rstgames.com/"
    }
}
