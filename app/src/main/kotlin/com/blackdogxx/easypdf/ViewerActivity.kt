package com.blackdogxx.easypdf

import android.content.ClipData
import android.content.Intent
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.util.SparseArray
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.commitNow
import androidx.pdf.view.PdfView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.FileNotFoundException

class ViewerActivity : AppCompatActivity(), EasyPdfFragment.Host {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var uri: Uri
    private var displayName = "PDF"
    private var pdfView: PdfView? = null
    private var pageCount = 0
    private var currentPage = 0
    private var startPage = 0
    private var loaded = false

    private val fragment: EasyPdfFragment?
        get() = supportFragmentManager.findFragmentByTag(TAG) as? EasyPdfFragment

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
        setSupportActionBar(toolbar)
        applyBarInsets(findViewById(R.id.root), findViewById(R.id.status_spacer), toolbar)

        displayName = queryName(uri)
        title = displayName
        toolbar.setOnClickListener { showGoToPage() }

        if (savedInstanceState == null) {
            // Постоянный доступ дают не все приложения-источники; если не дали — не страшно.
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
            startPage = Recents.find(this, uri.toString())?.page ?: 0

            val f = EasyPdfFragment()
            supportFragmentManager.commitNow {
                setReorderingAllowed(true)
                replace(R.id.container, f, TAG)
            }
            f.documentUri = uri
        } else {
            pageCount = savedInstanceState.getInt(STATE_PAGES, 0)
            currentPage = savedInstanceState.getInt(STATE_PAGE, 0)
            loaded = pageCount > 0
            updatePageLabel()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_PAGES, pageCount)
        outState.putInt(STATE_PAGE, currentPage)
    }

    override fun onStop() {
        super.onStop()
        if (loaded) Recents.setPage(this, uri.toString(), currentPage)
    }

    // --- события просмотрщика ---

    override fun onPdfViewReady(pdfView: PdfView) {
        this.pdfView = pdfView
        pdfView.addOnViewportChangedListener(object : PdfView.OnViewportChangedListener {
            override fun onViewportChanged(
                firstVisiblePage: Int,
                visiblePagesCount: Int,
                pageLocations: SparseArray<RectF>,
                zoomLevel: Float,
            ) {
                if (firstVisiblePage != currentPage) {
                    currentPage = firstVisiblePage
                    updatePageLabel()
                }
            }
        })
        val target = startPage
        if (target > 0) {
            startPage = 0
            pdfView.addOnFirstContentLoadListener { pdfView.scrollToPage(target) }
        }
    }

    override fun onDocumentLoaded(pageCount: Int) {
        this.pageCount = pageCount
        loaded = true
        Recents.touch(this, uri.toString(), displayName, pageCount)
        updatePageLabel()
    }

    override fun onDocumentError(error: Throwable) {
        if (error is SecurityException || error is FileNotFoundException) {
            Recents.remove(this, uri.toString())
        }
        if (isFinishing || isDestroyed) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.open_error)
            .setMessage(error.localizedMessage ?: error.javaClass.simpleName)
            .setPositiveButton(R.string.close) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    override fun onImmersiveMode(enter: Boolean) {
        toolbar.visibility = if (enter) View.GONE else View.VISIBLE
    }

    // --- меню ---

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.viewer, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_search -> {
            fragment?.let { it.isTextSearchActive = !it.isTextSearchActive }
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

    private fun totalPages(): Int =
        if (pageCount > 0) pageCount else pdfView?.pdfDocument?.pageCount ?: 0

    private fun updatePageLabel() {
        val total = totalPages()
        supportActionBar?.subtitle =
            if (total > 0) getString(R.string.page_of, currentPage + 1, total) else null
    }

    private fun showGoToPage() {
        val total = totalPages()
        val view = pdfView
        if (total <= 0 || view == null) {
            Toast.makeText(this, R.string.not_loaded, Toast.LENGTH_SHORT).show()
            return
        }
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.page_hint, total)
            imeOptions = EditorInfo.IME_ACTION_GO
            setSingleLine()
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        fun go() {
            val n = input.text.toString().toIntOrNull() ?: return
            view.scrollToPage(n.coerceIn(1, total) - 1)
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
        dialog.window?.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
        )
        dialog.show()
        input.requestFocus()
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
        const val TAG = "pdf"
        const val STATE_PAGES = "pages"
        const val STATE_PAGE = "page"
    }
}
