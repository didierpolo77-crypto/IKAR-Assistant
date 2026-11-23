package com.ikar.assistant.security

import android.app.Activity
import android.app.AlertDialog
import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.SafeBrowsingResponse
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.net.http.SslError
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Lightweight in-app browser. Uses Android WebView Safe Browsing for URLs
 * opened inside this activity. It does not filter traffic from other apps.
 */
class IkarSafeBrowserActivity : Activity() {
    private lateinit var browser: WebView
    private lateinit var address: EditText
    private lateinit var status: TextView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 18, 20, 12)
            setBackgroundColor(Color.rgb(7, 10, 24))
        }
        val heading = TextView(this).apply {
            text = "Navigation protégée IKAR"
            textSize = 20f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 12)
        }
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        address = EditText(this).apply {
            maxLines = 1
            setSingleLine(true)
            hint = "https://exemple.com"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            setSelectAllOnFocus(true)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val open = Button(this).apply {
            text = "Ouvrir"
            setOnClickListener { loadAddress(address.text.toString()) }
        }
        controls.addView(address)
        controls.addView(open)
        status = TextView(this).apply {
            text = "Initialisation de la protection Web…"
            textSize = 12f
            setTextColor(Color.rgb(120, 220, 235))
            setPadding(0, 4, 0, 8)
        }
        browser = WebView(this).apply {
            setBackgroundColor(Color.WHITE)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                settings.safeBrowsingEnabled = true
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val scheme = request.url.scheme?.lowercase()
                    if (scheme == "http" || scheme == "https") return false
                    Toast.makeText(this@IkarSafeBrowserActivity, "Protocole bloqué par IKAR.", Toast.LENGTH_SHORT).show()
                    return true
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                    status.text = "Chargement de la page…"
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    if (!url.isNullOrBlank()) {
                        address.setText(url)
                        status.text = "Page chargée · Safe Browsing Android et contrôles locaux actifs selon disponibilité."
                    }
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: android.webkit.WebResourceError
                ) {
                    if (request.isForMainFrame) {
                        status.text = "Échec du chargement : ${error.description}. Vérifie Internet ou l'adresse."
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView,
                    request: WebResourceRequest,
                    errorResponse: android.webkit.WebResourceResponse
                ) {
                    if (request.isForMainFrame) {
                        status.text = "Le site a répondu HTTP ${errorResponse.statusCode}."
                    }
                }

                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                    handler.cancel()
                    status.text = "Connexion TLS invalide : navigation bloquée."
                }

                @androidx.annotation.RequiresApi(Build.VERSION_CODES.O_MR1)
                override fun onSafeBrowsingHit(
                    view: WebView,
                    request: WebResourceRequest,
                    threatType: Int,
                    callback: SafeBrowsingResponse
                ) {
                    status.text = "Menace Web détectée par Safe Browsing : navigation bloquée."
                    callback.backToSafety(true)
                }
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        root.addView(heading)
        root.addView(controls)
        root.addView(status)
        root.addView(browser)
        setContentView(root)

        val incoming = intent?.data
        val initial = incoming?.toString()
            ?: intent?.getStringExtra(IntentCompat.EXTRA_TEXT)
            ?: "https://www.google.com"
        address.setText(initial)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            status.text = "Initialisation de Safe Browsing · chargement de la page…"
            // Navigation must not depend on the asynchronous Safe Browsing callback:
            // some WebView providers may delay or fail that callback.
            loadAddress(initial)
            WebView.startSafeBrowsing(applicationContext) { ready ->
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        status.text = if (ready) {
                            "Safe Browsing Android activé · menaces connues vérifiées"
                        } else {
                            "Safe Browsing indisponible · prudence renforcée"
                        }
                    }
                }
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            status.text = "Safe Browsing WebView activé · protection selon la version Android"
            loadAddress(initial)
        } else {
            status.text = "Protection Web avancée indisponible sur cette version Android."
            loadAddress(initial)
        }
    }

    private fun loadAddress(raw: String) {
        val value = raw.trim()
        val normalized = if (value.startsWith("http://", true) || value.startsWith("https://", true)) {
            value
        } else {
            "https://$value"
        }
        val uri = runCatching { Uri.parse(normalized) }.getOrNull()
        if (uri == null || uri.host.isNullOrBlank() ||
            uri.scheme?.lowercase() !in setOf("http", "https")
        ) {
            status.text = "Adresse invalide : seuls HTTP et HTTPS sont acceptés."
            return
        }
        address.setText(normalized)
        val localAssessment = SecurityCenter.checkLink(normalized)
        if (localAssessment.suspicious) {
            status.text = "Lien à vérifier · navigation suspendue en attendant ton accord."
            AlertDialog.Builder(this)
                .setTitle("Vérification de sécurité")
                .setMessage(localAssessment.reasons.joinToString("\n\n") +
                    "\n\nIKAR ne peut pas confirmer à lui seul qu'un lien est malveillant. Veux-tu quand même l'ouvrir ?")
                .setNegativeButton("Retour", null)
                .setPositiveButton("Continuer") { _, _ ->
                    status.text = "Ouverture avec avertissement · Safe Browsing Android en complément."
                    browser.loadUrl(normalized)
                }
                .show()
        } else {
            status.text = "Aucun signal heuristique local évident · contrôle Safe Browsing en complément."
            browser.loadUrl(normalized)
        }
    }

    override fun onBackPressed() {
        if (::browser.isInitialized && browser.canGoBack()) browser.goBack()
        else super.onBackPressed()
    }

    override fun onDestroy() {
        if (::browser.isInitialized) {
            browser.stopLoading()
            browser.destroy()
        }
        super.onDestroy()
    }

    private object IntentCompat {
        const val EXTRA_TEXT = "android.intent.extra.TEXT"
    }
}
