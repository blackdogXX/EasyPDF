package com.blackdogxx.easypdf

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.provider.OpenableColumns
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Экран просмотра. Документ рисует pdf.js внутри WebView (страница из assets/viewer),
 * всё остальное — панель, поиск, переход к странице, «Поделиться» — обычный Android.
 * Сети у приложения нет: WebView получает только файлы из assets и сам документ.
 */
class ViewerActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var web: WebView
    private lateinit var bar: View
    private lateinit var backChip: View
    private lateinit var searchBar: View
    private lateinit var searchInput: EditText
    private lateinit var searchCount: TextView
    private lateinit var uri: Uri

    private var displayName = "PDF"
    private var pageCount = 0
    private var firstPage = 1   // с единицы, как в интерфейсе
    private var lastPage = 1
    private var loaded = false
    private var searchOpen = false
    private var rotation = 0
    private var historyCount = 0
    private var barShown = true
    private var chipUntil = 0L   // до какого момента кнопку «Вернуться» держим на экране
    private val hideChip = Runnable { if (!barShown) backChip.visibility = View.GONE }
    private var zoom = 1.0

    // Большой файл отдаём просмотрщику частями (см. Client); для обычных эти поля пусты.
    private var docFd: ParcelFileDescriptor? = null
    private var docChannel: FileChannel? = null
    private var docSize = 0L
    private var outline: List<Pair<String, Int>> = emptyList()   // заголовок и уровень вложенности

    private val settings by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    private val saveCopy = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { target -> if (target != null) copyTo(target) }

    private val handler = Handler(Looper.getMainLooper())
    private val searchRunnable = Runnable { runSearch(again = false, previous = false) }

    private val searchBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = closeSearch()
    }

    /** Системная «Назад» закрывает приложение целиком, а не возвращает к списку файлов. */
    private val closeApp = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() = finishAffinity()
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
        bar = findViewById(R.id.bar)
        backChip = findViewById(R.id.back_chip)
        backChip.setOnClickListener { js("EasyPDF.back()") }
        searchBar = findViewById(R.id.search_bar)
        searchInput = findViewById(R.id.search_input)
        searchCount = findViewById(R.id.search_count)
        val bottomSpacer = findViewById<View>(R.id.bottom_spacer)

        setSupportActionBar(toolbar)
        val statusSpacer = findViewById<View>(R.id.status_spacer)
        val content = findViewById<View>(R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            statusSpacer.updateLayoutParams { height = bars.top }
            content.updatePadding(top = bars.top)
            bar.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin = bars.top }
            bar.updatePadding(left = bars.left, right = bars.right)
            val bottom = maxOf(bars.bottom, ime.bottom)
            bottomSpacer.updateLayoutParams { height = bottom }
            backChip.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = bottom + (16 * resources.displayMetrics.density).toInt()
                marginStart = bars.left + (16 * resources.displayMetrics.density).toInt()
            }
            insets
        }
        applyKeepScreenOn()

        displayName = queryName(uri)
        title = displayName
        toolbar.setOnClickListener { showGoToPage() }
        // Стрелка в панели ведёт к списку недавних документов.
        toolbar.setNavigationIcon(R.drawable.ic_back)
        toolbar.setNavigationContentDescription(R.string.to_recents)
        toolbar.setNavigationOnClickListener { openRecents() }
        onBackPressedDispatcher.addCallback(this, closeApp)
        onBackPressedDispatcher.addCallback(this, searchBack)   // добавлен позже — срабатывает первым
        setupSearch()

        var startPage = savedInstanceState?.getInt(STATE_PAGE, 0) ?: 0
        if (savedInstanceState == null) {
            // Постоянный доступ дают не все приложения-источники; если не дали — не страшно.
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
        }
        val recent = Recents.find(this, uri.toString())
        if (startPage <= 0) startPage = (recent?.page ?: 0) + 1
        rotation = recent?.rotation ?: 0
        zoom = recent?.zoom ?: 1.0
        val range = if (openForRanges()) 1 else 0

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
        val night = if (settings.getBoolean(PREF_NIGHT, false)) 1 else 0
        web.loadUrl(
            "$ORIGIN/viewer/viewer.html?page=$startPage&top=$BAR_DP&night=$night" +
                "&rot=$rotation&zoom=$zoom&range=$range"
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (loaded) outState.putInt(STATE_PAGE, firstPage)
    }

    override fun onStop() {
        super.onStop()
        if (loaded) Recents.setView(this, uri.toString(), firstPage - 1, rotation, zoom)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::web.isInitialized) {
            web.stopLoading()
            web.destroy()
        }
        try {
            docChannel?.close()
            docFd?.close()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    /**
     * Если файл большой и лежит в хранилище с произвольным доступом, открываем его один раз
     * и дальше отдаём pdf.js кусками по запросу — документ не грузится в память целиком.
     */
    private fun openForRanges(): Boolean {
        try {
            val fd = contentResolver.openFileDescriptor(uri, "r") ?: return false
            val size = fd.statSize
            if (size < RANGE_THRESHOLD) {
                fd.close()
                return false
            }
            val channel = FileInputStream(fd.fileDescriptor).channel
            channel.read(ByteBuffer.allocate(1), 0)   // проверка, что чтение с позиции возможно
            docFd = fd
            docChannel = channel
            docSize = size
            return true
        } catch (_: Exception) {
            return false
        }
    }

    private fun serveDocument(request: WebResourceRequest): WebResourceResponse? {
        val channel = docChannel
        if (channel == null) {
            val stream = contentResolver.openInputStream(uri) ?: return null
            return WebResourceResponse("application/pdf", null, stream)
        }
        val header = request.requestHeaders?.entries?.firstOrNull { it.key.equals("Range", true) }?.value
        val match = header?.let { Regex("bytes=(\\d+)-(\\d*)").find(it) }
        if (match == null) {
            // Первый запрос: по заголовкам pdf.js понимает, что можно читать частями, и обрывает его.
            val stream = contentResolver.openInputStream(uri) ?: return null
            return WebResourceResponse(
                "application/pdf", null, 200, "OK",
                mapOf("Accept-Ranges" to "bytes", "Content-Length" to docSize.toString()), stream,
            )
        }
        val start = match.groupValues[1].toLong()
        var end = minOf(match.groupValues[2].toLongOrNull() ?: (docSize - 1), docSize - 1)
        if (start > end) return null
        end = minOf(end, start + MAX_RANGE - 1)
        val buffer = ByteBuffer.allocate((end - start + 1).toInt())
        var position = start
        while (buffer.hasRemaining()) {
            val read = channel.read(buffer, position)
            if (read < 0) break
            position += read
        }
        val length = buffer.position()
        return WebResourceResponse(
            "application/pdf", null, 206, "Partial Content",
            mapOf(
                "Accept-Ranges" to "bytes",
                "Content-Range" to "bytes $start-${start + length - 1}/$docSize",
                "Content-Length" to length.toString(),
            ),
            ByteArrayInputStream(buffer.array(), 0, length),
        )
    }

    // --- WebView: выдаём только свои файлы и сам документ ---

    private inner class Client : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
            val url = request.url
            val path = url.path ?: ""
            if (url.host != HOST || path.contains("..")) return notFound()
            return try {
                when {
                    path == "/doc/current.pdf" -> serveDocument(request) ?: notFound()
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
        fun onBar(show: Boolean) = runOnUiThread { showBar(show) }

        @JavascriptInterface
        fun onHistory(count: Int) = runOnUiThread {
            historyCount = count
            handler.removeCallbacks(hideChip)
            if (count > 0) {
                // После перехода кнопка «Вернуться» видна несколько секунд, даже если панель спряталась.
                backChip.visibility = View.VISIBLE
                chipUntil = System.currentTimeMillis() + CHIP_MS
                handler.postDelayed(hideChip, CHIP_MS)
            } else {
                backChip.visibility = View.GONE
            }
        }

        @JavascriptInterface
        fun onView(rotation: Int, zoom: Double) = runOnUiThread {
            this@ViewerActivity.rotation = rotation
            this@ViewerActivity.zoom = zoom
        }

        @JavascriptInterface
        fun onOutline(json: String) = runOnUiThread {
            outline = try {
                val array = JSONArray(json)
                (0 until array.length()).map { i ->
                    val o = array.getJSONObject(i)
                    o.optString("t") to o.optInt("d")
                }
            } catch (_: Exception) {
                emptyList()
            }
        }
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
        R.id.action_outline -> {
            showOutline()
            true
        }
        R.id.action_bookmarks -> {
            showBookmarks()
            true
        }
        R.id.action_rotate -> {
            js("EasyPDF.rotate()")
            true
        }
        R.id.action_night -> {
            val on = !settings.getBoolean(PREF_NIGHT, false)
            settings.edit().putBoolean(PREF_NIGHT, on).apply()
            js("EasyPDF.setNight($on)")
            true
        }
        R.id.action_keep_on -> {
            settings.edit().putBoolean(PREF_KEEP_ON, !settings.getBoolean(PREF_KEEP_ON, false)).apply()
            applyKeepScreenOn()
            true
        }
        R.id.action_save -> {
            try {
                saveCopy.launch(if (displayName.endsWith(".pdf", ignoreCase = true)) displayName else "$displayName.pdf")
            } catch (_: Exception) {
                Toast.makeText(this, R.string.save_error, Toast.LENGTH_SHORT).show()
            }
            true
        }
        R.id.action_print -> {
            printDocument()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_night)?.isChecked = settings.getBoolean(PREF_NIGHT, false)
        menu.findItem(R.id.action_keep_on)?.isChecked = settings.getBoolean(PREF_KEEP_ON, false)
        return super.onPrepareOptionsMenu(menu)
    }

    private fun openRecents() {
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        )
        finish()
    }

    private fun applyKeepScreenOn() {
        if (settings.getBoolean(PREF_KEEP_ON, false)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /** Панель уезжает вверх, за полоску строки состояния, и возвращается обратно. */
    private fun showBar(show: Boolean) {
        if (searchOpen && !show) return
        barShown = show
        // Кнопка «Вернуться» показывается вместе с панелью, пока есть куда возвращаться.
        if (show && historyCount > 0) {
            backChip.visibility = View.VISIBLE
        } else if (!show && System.currentTimeMillis() > chipUntil) {
            backChip.visibility = View.GONE
        }
        bar.animate()
            .translationY(if (show) 0f else -bar.height.toFloat())
            .setDuration(180)
            .start()
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
        searchOpen = true
        js("EasyPDF.setSearchMode(true)")
        showBar(true)
        toolbar.visibility = View.INVISIBLE
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
        searchOpen = false
        js("EasyPDF.findClose()")
        js("EasyPDF.setSearchMode(false)")
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

    private fun showOutline() {
        if (outline.isEmpty()) {
            Toast.makeText(this, R.string.no_outline, Toast.LENGTH_SHORT).show()
            return
        }
        val items = outline.map { (title, depth) -> "\u2003".repeat(depth.coerceAtMost(6)) + title }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.outline)
            .setItems(items.toTypedArray()) { _, index -> js("EasyPDF.goToOutline($index)") }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun showBookmarks() {
        if (!loaded) {
            Toast.makeText(this, R.string.not_loaded, Toast.LENGTH_SHORT).show()
            return
        }
        val key = uri.toString()
        val list = Bookmarks.load(this, key)
        val page = firstPage
        val exists = list.any { it.page == page }
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.bookmarks)
            .setNegativeButton(R.string.close, null)
        if (list.isEmpty()) {
            builder.setMessage(R.string.no_bookmarks)
        } else {
            val items = list.map {
                if (it.title.isBlank()) getString(R.string.bookmark_item_plain, it.page)
                else getString(R.string.bookmark_item, it.page, it.title)
            }
            builder.setItems(items.toTypedArray()) { _, index ->
                js("EasyPDF.goToPage(${list[index].page.coerceIn(1, maxOf(1, pageCount))})")
            }
        }
        if (exists) {
            builder.setPositiveButton(getString(R.string.bookmark_remove, page)) { _, _ ->
                Bookmarks.removePage(this, key, page)
            }
        } else {
            builder.setPositiveButton(getString(R.string.bookmark_add, page)) { _, _ -> addBookmark(page) }
        }
        builder.show()
    }

    private fun addBookmark(page: Int) {
        val (input, box) = dialogInput(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
            getString(R.string.bookmark_name_hint),
        )
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.bookmark_new, page))
            .setView(box)
            .setPositiveButton(R.string.add) { _, _ ->
                Bookmarks.add(this, uri.toString(), page, input.text.toString().trim())
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.show()
        input.requestFocus()
    }

    private fun copyTo(target: Uri) {
        Thread {
            val ok = try {
                contentResolver.openInputStream(uri)!!.use { input ->
                    contentResolver.openOutputStream(target, "w")!!.use { output -> input.copyTo(output) }
                }
                true
            } catch (_: Exception) {
                false
            }
            runOnUiThread {
                Toast.makeText(this, if (ok) R.string.saved else R.string.save_error, Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    /** Печать: отдаём системе исходный файл как есть. */
    private fun printDocument() {
        val manager = getSystemService(PrintManager::class.java)
        if (manager == null) {
            Toast.makeText(this, R.string.print_error, Toast.LENGTH_SHORT).show()
            return
        }
        val adapter = object : PrintDocumentAdapter() {
            override fun onLayout(
                oldAttributes: PrintAttributes?,
                newAttributes: PrintAttributes?,
                cancellationSignal: CancellationSignal?,
                callback: LayoutResultCallback?,
                extras: Bundle?,
            ) {
                if (cancellationSignal?.isCanceled == true) {
                    callback?.onLayoutCancelled()
                    return
                }
                val info = PrintDocumentInfo.Builder(displayName)
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(if (pageCount > 0) pageCount else PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                    .build()
                callback?.onLayoutFinished(info, oldAttributes != newAttributes)
            }

            override fun onWrite(
                pages: Array<out PageRange>?,
                destination: ParcelFileDescriptor?,
                cancellationSignal: CancellationSignal?,
                callback: WriteResultCallback?,
            ) {
                Thread {
                    try {
                        contentResolver.openInputStream(uri)!!.use { input ->
                            FileOutputStream(destination!!.fileDescriptor).use { output -> input.copyTo(output) }
                        }
                        callback?.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                    } catch (e: Exception) {
                        callback?.onWriteFailed(e.message)
                    }
                }.start()
            }
        }
        try {
            manager.print(displayName, adapter, null)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.print_error, Toast.LENGTH_SHORT).show()
        }
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
        const val CHIP_MS = 8000L
        const val BAR_DP = 64   // высота панели в activity_viewer.xml
        const val PREF_NIGHT = "night"
        const val PREF_KEEP_ON = "keep_on"
        const val RANGE_THRESHOLD = 20L * 1024 * 1024   // с какого размера читать файл частями
        const val MAX_RANGE = 8L * 1024 * 1024
    }
}
