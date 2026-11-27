package com.ikar.assistant.security

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.os.Environment
import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.SafeBrowsingResponse
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.PermissionRequest
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
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * Lightweight in-app browser. Uses Android WebView Safe Browsing for URLs
 * opened inside this activity. It does not filter traffic from other apps.
 */
class IkarSafeBrowserActivity : Activity() {
    private lateinit var browser: WebView
    private lateinit var address: EditText
    private lateinit var status: TextView
    private val mainHandler = Handler(Looper.getMainLooper())
    private var safeBrowsingReady: Boolean? = null
    private var pendingInitialUrl: String? = null
    private var initialNavigationStarted = false
    private var currentNavigationSafeBrowsing = false
    @Volatile private var privacyModeEnabled = true
    private val blockedTrackerRequests = AtomicInteger(0)
    private val blockedThreatRequests = AtomicInteger(0)
    private lateinit var privacyButton: Button

    private val safeBrowsingTimeout = Runnable {
        if (!initialNavigationStarted && !isFinishing && !isDestroyed) {
            safeBrowsingReady = false
            status.text = "Safe Browsing n'a pas répondu à temps · ouverture avec contrôles locaux uniquement."
            startInitialNavigation()
        }
    }

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
        privacyButton = Button(this).apply {
            text = "Confidentialité : activée"
            setOnClickListener {
                privacyModeEnabled = !privacyModeEnabled
                applyPrivacySettings()
                text = if (privacyModeEnabled) "Confidentialité : activée" else "Confidentialité : standard"
                status.text = if (privacyModeEnabled) {
                    "Protection vie privée active : cookies tiers bloqués, pisteurs connus filtrés, géolocalisation refusée, cache désactivé. Ce mode ne masque pas ton adresse IP."
                } else {
                    "Mode standard : les cookies tiers peuvent être acceptés. La géolocalisation reste refusée."
                }
            }
        }
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
            settings.setGeolocationEnabled(false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                settings.safeBrowsingEnabled = true
            }
            webChromeClient = object : WebChromeClient() {
                override fun onGeolocationPermissionsShowPrompt(
                    origin: String,
                    callback: android.webkit.GeolocationPermissions.Callback
                ) {
                    callback.invoke(origin, false, false)
                }

                override fun onPermissionRequest(request: PermissionRequest) {
                    request.deny()
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val scheme = request.url.scheme?.lowercase()
                    if (scheme != "http" && scheme != "https") {
                        Toast.makeText(this@IkarSafeBrowserActivity, "Protocole bloqué par IKAR.", Toast.LENGTH_SHORT).show()
                        return true
                    }
                    // Re-check every top-level navigation, including redirects and clicked links.
                    if (request.isForMainFrame) {
                        val target = request.url.toString()
                        val host = request.url.host.orEmpty()
                        if (runCatching { ThreatIntelStore.isKnownMaliciousHost(this@IkarSafeBrowserActivity, host) }.getOrDefault(false)) {
                            status.text = "Destination bloquée : domaine présent dans la base locale de menaces."
                            Toast.makeText(this@IkarSafeBrowserActivity, "Domaine malveillant connu bloqué par IKAR.", Toast.LENGTH_LONG).show()
                            return true
                        }
                        val assessment = SecurityCenter.checkLink(target)
                        if (assessment.suspicious) {
                            AlertDialog.Builder(this@IkarSafeBrowserActivity)
                                .setTitle("Lien à vérifier")
                                .setMessage(assessment.reasons.joinToString("\n\n") +
                                    "\n\nIKAR ne peut pas certifier qu'un site est malveillant. Continuer malgré cet avertissement ?")
                                .setNegativeButton("Bloquer", null)
                                .setPositiveButton("Continuer") { _, _ -> view.loadUrl(target) }
                                .show()
                            status.text = "Navigation suspendue : destination à vérifier."
                            return true
                        }
                    }
                    return false
                }

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): android.webkit.WebResourceResponse? {
                    val host = request.url.host
                    if (!request.isForMainFrame && !host.isNullOrBlank() &&
                        runCatching { ThreatIntelStore.isKnownMaliciousHost(this@IkarSafeBrowserActivity, host) }.getOrDefault(false)
                    ) {
                        val total = blockedThreatRequests.incrementAndGet()
                        mainHandler.post {
                            if (!isFinishing && !isDestroyed) {
                                status.text = "Web Shield · $total ressource(s) issue(s) d'un domaine malveillant bloquée(s)."
                            }
                        }
                        return android.webkit.WebResourceResponse(
                            "text/plain", "UTF-8", 403, "Blocked by IKAR", emptyMap(),
                            ByteArrayInputStream(ByteArray(0))
                        )
                    }
                    if (privacyModeEnabled && !request.isForMainFrame && IkarTrackerBlocklist.isTrackerHost(host)) {
                        val total = blockedTrackerRequests.incrementAndGet()
                        mainHandler.post {
                            if (!isFinishing && !isDestroyed) {
                                status.text = "Confidentialité active · $total requête(s) de pistage bloquée(s) dans cette session."
                            }
                        }
                        return android.webkit.WebResourceResponse(
                            "text/plain",
                            "UTF-8",
                            204,
                            "No Content",
                            emptyMap(),
                            ByteArrayInputStream(ByteArray(0))
                        )
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                    currentNavigationSafeBrowsing = safeBrowsingReady == true
                    status.text = if (currentNavigationSafeBrowsing) {
                        "Chargement · Safe Browsing Android activé."
                    } else {
                        "Chargement · Safe Browsing non confirmé, prudence renforcée."
                    }
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    if (!url.isNullOrBlank()) {
                        address.setText(url)
                        val trackerCount = blockedTrackerRequests.get()
                        val trackerSummary = if (privacyModeEnabled && trackerCount > 0) {
                            " · $trackerCount requête(s) de pistage bloquée(s) dans cette session."
                        } else {
                            ""
                        }
                        status.text = (if (currentNavigationSafeBrowsing) {
                            "Page chargée · Safe Browsing Android activé pour cette navigation."
                        } else {
                            "Page chargée · Safe Browsing non confirmé pour cette navigation ; contrôles locaux heuristiques uniquement."
                        }) + trackerSummary
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
        root.addView(privacyButton)
        root.addView(status)
        root.addView(browser)
        browser.setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            handleDownload(url, userAgent, contentDisposition, mimeType, contentLength)
        }
        setContentView(root)
        privacyModeEnabled = getSharedPreferences("ikar_browser_privacy", MODE_PRIVATE)
            .getBoolean("privacy_mode_enabled", true)
        applyPrivacySettings()
        privacyButton.text = if (privacyModeEnabled) "Confidentialité : activée" else "Confidentialité : standard"
        status.text = "Confidentialité active : cookies tiers bloqués, pisteurs connus filtrés, géolocalisation refusée. Ton adresse IP reste visible aux sites."

        val incoming = intent?.data
        val initial = incoming?.toString()
            ?: intent?.getStringExtra(IntentCompat.EXTRA_TEXT)
            ?: "https://www.google.com"
        address.setText(initial)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            pendingInitialUrl = initial
            status.text = "Initialisation de Safe Browsing · navigation en attente."
            // Do not load the first page before WebView reports whether Safe Browsing
            // initialized. If the provider never calls back, continue after a timeout
            // with an explicit warning rather than silently claiming protection.
            mainHandler.postDelayed(safeBrowsingTimeout, 4_000L)
            WebView.startSafeBrowsing(applicationContext) { ready ->
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        mainHandler.removeCallbacks(safeBrowsingTimeout)
                        safeBrowsingReady = ready
                        if (ready) {
                            status.text = "Safe Browsing Android activé."
                        } else {
                            status.text = "Safe Browsing indisponible · contrôles locaux uniquement."
                        }
                        startInitialNavigation()
                    }
                }
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            safeBrowsingReady = null
            status.text = "Safe Browsing WebView configuré selon le fournisseur Android ; activation non vérifiable ici."
            loadAddress(initial)
        } else {
            safeBrowsingReady = false
            status.text = "Protection Safe Browsing indisponible sur cette version Android."
            loadAddress(initial)
        }
    }

    /**
     * Blocks known malicious destinations and executable/script payloads.
     * Archives and macro-enabled Office documents require explicit confirmation.
     * Other files use Android DownloadManager; this is not an antivirus scan.
     */
    private fun handleDownload(rawUrl: String, userAgent: String?, contentDisposition: String?, mimeType: String?, contentLength: Long) {
        val downloadUri = runCatching { Uri.parse(rawUrl) }.getOrNull()
        val scheme = downloadUri?.scheme?.lowercase()
        val rawHost = downloadUri?.host.orEmpty()
        if (downloadUri == null || rawHost.isBlank() || scheme != "https") {
            status.text = "Téléchargement bloqué : URL invalide ou connexion non HTTPS."
            Toast.makeText(this, "IKAR bloque ce téléchargement non sécurisé.", Toast.LENGTH_LONG).show()
            return
        }
        val host = runCatching { java.net.IDN.toASCII(rawHost.trimEnd('.'), java.net.IDN.USE_STD3_ASCII_RULES).lowercase() }.getOrDefault("")
        if (host.isBlank() || runCatching { ThreatIntelStore.isKnownMaliciousHost(this, host) }.getOrDefault(false)) {
            status.text = "Téléchargement bloqué : domaine répertorié comme malveillant."
            Toast.makeText(this, "Web Shield a bloqué le téléchargement.", Toast.LENGTH_LONG).show()
            return
        }
        val assessment = SecurityCenter.checkLink(rawUrl)
        if (assessment.suspicious) {
            status.text = "Téléchargement bloqué : l'URL présente des signaux suspects."
            AlertDialog.Builder(this).setTitle("Téléchargement bloqué par Web Shield")
                .setMessage(assessment.reasons.joinToString("\n\n") + "\n\nIKAR refuse ce téléchargement car sa destination présente des signaux de risque.")
                .setPositiveButton("Compris", null).show()
            return
        }
        val filename = extractDownloadFilename(contentDisposition, downloadUri)
        val extension = filename.substringAfterLast('.', "").lowercase()
        val normalizedMime = mimeType.orEmpty().substringBefore(';').trim().lowercase()
        val executableExtensions = setOf(
            "apk", "xapk", "apks", "aab", "exe", "msi", "msp", "com", "scr", "pif",
            "dll", "so", "dylib", "dmg", "pkg", "deb", "rpm", "jar", "class", "dex",
            "sh", "bash", "zsh", "bat", "cmd", "ps1", "psm1", "vbs", "vbe", "js",
            "jse", "hta", "wsf", "wsh", "reg", "lnk"
        )
        val executableMimeTypes = setOf(
            "application/vnd.android.package-archive", "application/x-msdownload",
            "application/x-dosexec", "application/x-executable", "application/x-sh",
            "application/x-shellscript", "application/java-archive", "application/x-java-archive",
            "application/x-sharedlib"
        )
        val isExecutable = extension in executableExtensions ||
            normalizedMime in executableMimeTypes ||
            normalizedMime.startsWith("application/x-executable")
        if (isExecutable) {
            status.text = "Téléchargement bloqué : fichier exécutable ou script à haut risque ($filename)."
            AlertDialog.Builder(this).setTitle("Fichier dangereux bloqué")
                .setMessage("IKAR a bloqué « $filename » ($normalizedMime). Les applications, exécutables, bibliothèques et scripts peuvent installer du code ou compromettre l'appareil. Ce blocage est préventif ; aucune analyse antivirus complète du fichier n'a été effectuée.")
                .setPositiveButton("Fermer", null).show()
            return
        }
        val archiveExtensions = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "iso", "img")
        val macroExtensions = setOf("docm", "xlsm", "pptm", "dotm", "xltm", "potm")
        val isContainerOrMacro = extension in archiveExtensions || extension in macroExtensions ||
            normalizedMime in setOf(
                "application/zip", "application/x-7z-compressed", "application/vnd.rar",
                "application/x-rar-compressed", "application/vnd.ms-word.document.macroenabled.12",
                "application/vnd.ms-excel.sheet.macroenabled.12",
                "application/vnd.ms-powerpoint.presentation.macroenabled.12"
            )
        val proceed = { enqueueDownload(rawUrl, filename, userAgent, normalizedMime, contentLength) }
        if (isContainerOrMacro) {
            AlertDialog.Builder(this).setTitle("Fichier à examiner avec prudence")
                .setMessage("« $filename » est une archive ou un document pouvant contenir des macros. IKAR ne peut pas inspecter son contenu avant téléchargement. Ne l'ouvre pas si tu ne connais pas sa provenance.\n\nContinuer le téléchargement ?")
                .setNegativeButton("Bloquer", null)
                .setPositiveButton("Télécharger") { _, _ -> proceed() }.show()
        } else proceed()
    }

    private fun extractDownloadFilename(contentDisposition: String?, uri: Uri): String {
        val dispositionName = contentDisposition?.let {
            Regex("""filename\*?=(?:UTF-8''|")?([^";]+)""", RegexOption.IGNORE_CASE)
                .find(it)?.groupValues?.getOrNull(1)
        }?.let { Uri.decode(it.trim().trim('"')) }
        val candidate = dispositionName ?: uri.lastPathSegment?.let(Uri::decode) ?: "ikar-download"
        val safe = candidate.replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_")
            .trim().trimEnd('.').take(120)
        return safe.ifBlank { "ikar-download" }
    }

    private fun enqueueDownload(url: String, filename: String, userAgent: String?, mimeType: String, contentLength: Long) {
        runCatching {
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle(filename)
                setDescription("Téléchargement demandé depuis le navigateur IKAR")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(false)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
                if (mimeType.isNotBlank()) setMimeType(mimeType)
                if (!userAgent.isNullOrBlank()) addRequestHeader("User-Agent", userAgent)
                CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }?.let {
                    addRequestHeader("Cookie", it)
                }
            }
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
        }.onSuccess {
            val sizeText = if (contentLength > 0L) " Taille annoncée : \${contentLength / 1024} Ko." else ""
            status.text = "Téléchargement lancé : $filename.$sizeText Fichier non encore analysé par l'antivirus IKAR."
            Toast.makeText(this, "Téléchargement lancé. Analyse antivirus non effectuée.", Toast.LENGTH_LONG).show()
        }.onFailure { error ->
            status.text = "Téléchargement impossible : \${error.localizedMessage ?: "erreur Android"}."
        }
    }

    private fun applyPrivacySettings() {
        if (!::browser.isInitialized) return
        CookieManager.getInstance().setAcceptCookie(true)
        browser.settings.javaScriptCanOpenWindowsAutomatically = false
        browser.settings.setSupportMultipleWindows(false)
        browser.settings.saveFormData = false
        CookieManager.getInstance().setAcceptThirdPartyCookies(browser, !privacyModeEnabled)
        browser.settings.cacheMode = if (privacyModeEnabled) {
            android.webkit.WebSettings.LOAD_NO_CACHE
        } else {
            android.webkit.WebSettings.LOAD_DEFAULT
        }
        getSharedPreferences("ikar_browser_privacy", MODE_PRIVATE).edit()
            .putBoolean("privacy_mode_enabled", privacyModeEnabled)
            .apply()
    }

    private fun startInitialNavigation() {
        if (initialNavigationStarted || isFinishing || isDestroyed) return
        initialNavigationStarted = true
        val initial = pendingInitialUrl
        pendingInitialUrl = null
        if (initial != null) loadAddress(initial)
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
        val parsedHost = uri.host.orEmpty()
        if (runCatching { ThreatIntelStore.isKnownMaliciousHost(this, parsedHost) }.getOrDefault(false)) {
            status.text = "Web Shield : ce domaine figure dans la base locale de menaces. Navigation bloquée."
            Toast.makeText(this, "Domaine malveillant connu bloqué par IKAR.", Toast.LENGTH_LONG).show()
            return
        }
        val localAssessment = SecurityCenter.checkLink(normalized)
        if (localAssessment.suspicious) {
            status.text = "Lien à vérifier · navigation suspendue en attendant ton accord."
            AlertDialog.Builder(this)
                .setTitle("Vérification de sécurité")
                .setMessage(localAssessment.reasons.joinToString("\n\n") +
                    "\n\nIKAR ne peut pas confirmer à lui seul qu'un lien est malveillant. Veux-tu quand même l'ouvrir ?")
                .setNegativeButton("Retour", null)
                .setPositiveButton("Continuer") { _, _ ->
                    status.text = if (safeBrowsingReady == true) {
                "Ouverture avec avertissement · Safe Browsing Android activé."
            } else {
                "Ouverture avec avertissement · Safe Browsing non confirmé."
            }
                    browser.loadUrl(normalized)
                }
                .show()
        } else {
            status.text = if (safeBrowsingReady == true) {
                "Aucun signal heuristique local évident · Safe Browsing Android en complément."
            } else {
                "Aucun signal heuristique local évident · Safe Browsing non confirmé."
            }
            browser.loadUrl(normalized)
        }
    }

    override fun onBackPressed() {
        if (::browser.isInitialized && browser.canGoBack()) browser.goBack()
        else super.onBackPressed()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(safeBrowsingTimeout)
        if (::browser.isInitialized) {
            browser.stopLoading()
            if (privacyModeEnabled) {
                // Clear this app's WebView browsing state on exit; this also signs out
                // of sites opened in this WebView and is intentionally disclosed.
                browser.clearHistory()
                browser.clearCache(true)
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                android.webkit.WebStorage.getInstance().deleteAllData()
            }
            browser.destroy()
        }
        super.onDestroy()
    }

    private object IntentCompat {
        const val EXTRA_TEXT = "android.intent.extra.TEXT"
    }
}
