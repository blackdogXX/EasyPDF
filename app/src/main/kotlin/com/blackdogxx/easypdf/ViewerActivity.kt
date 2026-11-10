package com.blackdogxx.easypdf

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject
import java.io.ByteArrayInputStream

/**
 * Экран просмотра. Документ рисует pdf.js внутри WebView (страница из assets/viewer),
 * всё остальное — панель, поиск, переход к странице, «Поделиться» — обычный Android.
 * Сети у приложения нет: WebView получает только файлы из assets и сам документ.
 */
class ViewerActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var web: WebView
    private lateinit var searchBar: View
    private lateinit var searchInput: EditText
    private lateinit var searchCount: TextView
    private lateinit var uri: Uri

    private var displayName = "PDF"
    private var pageCount = 0
    private var firstPage = 1   // с единицы, как в интерфейсе
    private var lastPage = 1
    private var loaded = false

    private val handler = Handler(Looper.getMainLooper())
    private val searchRunnable = Runnable { runSearch(again = false, previous = false) }

    private val searchBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = closeSearch()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val data = intent?.data
        if (data == null) {
            finish()
            return
        }
        uri = data

        setContentView(R.layout.activity_viewer)
        toolbar = findViewById(R.id.toolbar)
        web = findViewById(R.id.web)
        searchBar = findViewById(R.id.search_bar)
        searchInput = findViewById(R.id.search_input)
        searchCount = findViewById(R.id.search_count)
        val bottomSpacer = findViewById<View>(R.id.bottom_spacer)

        setSupportActionBar(toolbar)
        applyBarInsets(findViewById(R.id.root), findViewById(R.id.status_spacer), toolbar) { bottom ->
            bottomSpacer.updateLayoutParams { height = bottom }
        }

        displayName = queryName(uri)
        title = displayName
        toolbar.setOnClickListener { showGoToPage() }
        onBackPressedDispatcher.addCallback(this, searchBack)
        setupSearch()

        var startPage = savedInstanceState?.getInt(STATE_PAGE, 0) ?: 0
        if (savedInstanceState == null) {
            // Постоянный доступ дают не все приложения-источники; если не дали — не страшно.
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
        }
        if (startPage <= 0) startPage = (Recents.find(this, uri.toString())?.page ?: 0) + 1

        web.setBackgroundColor(Color.TRANSPARENT)
        web.settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
        }
        web.webViewClient = Client()
        web.addJavascriptInterface(Bridge(), "Android")
        web.loadUrl("$ORIGIN/viewer/viewer.html?page=$startPage")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (loaded) outState.putInt(STATE_PAGE, firstPage)
    }

    override fun onStop() {
        super.onStop()
        if (loaded) Recents.setPage(this, uri.toString(), firstPage - 1)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::web.isInitialized) {
            web.stopLoading()
            web.destroy()
        }
        super.onDestroy()
    }

    // --- WebView: выдаём только свои файлы и сам документ ---

    private inner class Client : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
            val url = request.url
            val path = url.path ?: ""
            if (url.host != HOST || path.contains("..")) return notFound()
            return try {
                when {
                    path == "/doc/current.pdf" -> {
                        val stream = contentResolver.openInputStream(uri) ?: return notFound()
                        WebResourceResponse("application/pdf", null, stream)
                    }
                    path.startsWith("/viewer/") -> {
                        val mime = when (path.substringAfterLast('.', "")) {
                            "html" -> "text/html"
                            "css" -> "text/css"
                            "js", "mjs" -> "text/javascript"
                            "svg" -> "image/svg+xml"
                            "gif" -> "image/gif"
                            "wasm" -> "application/wasm"
                            else -> "application/octet-stream"
                        }
                        val encoding = if (mime.startsWith("text/") || mime == "image/svg+xml") "utf-8" else null
                        WebResourceResponse(mime, encoding, assets.open(path.removePrefix("/")))
                    }
                    else -> notFound()
                }
            } catch (_: Exception) {
                notFound()
            }
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url
            if (url.host == HOST) return false
            // Ссылки из документа открываем во внешнем приложении.
            if (url.scheme == "http" || url.scheme == "https" || url.scheme == "mailto") {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, url))
                } catch (_: Exception) {
                }
            }
            return true
        }

        private fun notFound() = WebResourceResponse(
            "text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0))
        )
    }

    /** Вызовы из JavaScript приходят в фоновом потоке — переносим в главный. */
    private inner class Bridge {
        @JavascriptInterface
        fun onLoaded(pages: Int) = runOnUiThread {
            pageCount = pages
            loaded = true
            Recents.touch(this@ViewerActivity, uri.toString(), displayName, pages)
            updatePageLabel()
        }

        @JavascriptInterface
        fun onPages(first: Int, last: Int) = runOnUiThread {
            firstPage = first
            lastPage = last
            updatePageLabel()
        }

        @JavascriptInterface
        fun onError(message: String) = runOnUiThread { showError(message) }

        @JavascriptInterface
        fun onPassword(incorrect: Boolean) = runOnUiThread { askPassword(incorrect) }

        @JavascriptInterface
        fun onFind(current: Int, total: Int, state: Int) = runOnUiThread {
            searchCount.text = when {
                searchInput.text.isNullOrEmpty() -> ""
                total > 0 -> "$current/$total"
                state == FIND_NOT_FOUND -> getString(R.string.not_found)
                state == FIND_PENDING -> "…"
                else -> searchCount.text
            }
        }

        @JavascriptInterface
        fun onProgress(percent: Int) = Unit
    }

    private fun js(code: String) {
        if (::web.isInitialized) web.evaluateJavascript("window.EasyPDF && ($code)", null)
    }

    // --- меню ---

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.viewer, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_search -> {
            openSearch()
            true
        }
        R.id.action_goto -> {
            showGoToPage()
            true
        }
        R.id.action_share -> {
            share()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun updatePageLabel() {
        supportActionBar?.subtitle = when {
            pageCount <= 0 -> null
            lastPage > firstPage -> getString(R.string.pages_of, firstPage, lastPage, pageCount)
            else -> getString(R.string.page_of, firstPage, pageCount)
        }
    }

    // --- поиск ---

    private fun setupSearch() {
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                handler.removeCallbacks(searchRunnable)
                handler.postDelayed(searchRunnable, 350)
            }
        })
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                handler.removeCallbacks(searchRunnable)
                runSearch(again = true, previous = false)
                hideKeyboard()
                true
            } else {
                false
            }
        }
        findViewById<View>(R.id.search_next).setOnClickListener { runSearch(again = true, previous = false) }
        findViewById<View>(R.id.search_prev).setOnClickListener { runSearch(again = true, previous = true) }
        findViewById<View>(R.id.search_close).setOnClickListener { closeSearch() }
    }

    private fun openSearch() {
        if (!loaded) {
            Toast.makeText(this, R.string.not_loaded, Toast.LENGTH_SHORT).show()
            return
        }
        toolbar.visibility = View.GONE
        searchBar.visibility = View.VISIBLE
        searchBack.isEnabled = true
        searchInput.requestFocus()
        searchInput.selectAll()
        getSystemService(InputMethodManager::class.java)
            ?.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
        if (!searchInput.text.isNullOrEmpty()) runSearch(again = false, previous = false)
    }

    private fun closeSearch() {
        handler.removeCallbacks(searchRunnable)
        hideKeyboard()
        searchBar.visibility = View.GONE
        toolbar.visibility = View.VISIBLE
        searchBack.isEnabled = false
        searchCount.text = ""
        js("EasyPDF.findClose()")
    }

    private fun runSearch(again: Boolean, previous: Boolean) {
        val query = searchInput.text?.toString().orEmpty()
        if (query.isEmpty()) {
            searchCount.text = ""
            js("EasyPDF.findClose()")
            return
        }
        js("EasyPDF.find(${JSONObject.quote(query)}, $previous, $again)")
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(searchInput.windowToken, 0)
    }

    // --- диалоги ---

    private fun dialogInput(type: Int, hintText: String): Pair<EditText, View> {
        val input = EditText(this).apply {
            inputType = type
            hint = hintText
            setSingleLine()
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        return input to box
    }

    private fun showGoToPage() {
        if (pageCount <= 0) {
            Toast.makeText(this, R.string.not_loaded, Toast.LENGTH_SHORT).show()
            return
        }
        val (input, box) = dialogInput(InputType.TYPE_CLASS_NUMBER, getString(R.string.page_hint, pageCount))
        input.imeOptions = EditorInfo.IME_ACTION_GO
        fun go() {
            val n = input.text.toString().toIntOrNull() ?: return
            js("EasyPDF.goToPage(${n.coerceIn(1, pageCount)})")
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.go_to_page)
            .setView(box)
            .setPositiveButton(R.string.go) { _, _ -> go() }
            .setNegativeButton(R.string.cancel, null)
            .create()
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                go()
                dialog.dismiss()
                true
            } else {
                false
            }
        }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.show()
        input.requestFocus()
    }

    private fun askPassword(incorrect: Boolean) {
        if (isFinishing || isDestroyed) return
        val (input, box) = dialogInput(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            getString(R.string.password_hint),
        )
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.password_title)
            .setMessage(if (incorrect) getString(R.string.password_wrong) else null)
            .setView(box)
            .setPositiveButton(R.string.ok) { _, _ ->
                js("EasyPDF.password(${JSONObject.quote(input.text.toString())})")
            }
            .setNegativeButton(R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.show()
        input.requestFocus()
    }

    private fun showError(message: String) {
        if (isFinishing || isDestroyed) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.open_error)
            .setMessage(message)
            .setPositiveButton(R.string.close) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun share() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(displayName, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(send, null))
        } catch (_: Exception) {
            Toast.makeText(this, R.string.share_error, Toast.LENGTH_SHORT).show()
        }
    }

    private fun queryName(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val name = cursor.getString(0)
                        if (!name.isNullOrBlank()) return name
                    }
                }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment ?: "PDF"
    }

    private companion object {
        const val HOST = "appassets.androidplatform.net"
        const val ORIGIN = "https://$HOST"
        const val STATE_PAGE = "page"
        const val FIND_NOT_FOUND = 1
        const val FIND_PENDING = 3
    }
}
