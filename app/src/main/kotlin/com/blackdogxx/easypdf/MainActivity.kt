package com.blackdogxx.easypdf

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.DateFormat
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Date

class MainActivity : AppCompatActivity() {

    private lateinit var list: ListView
    private lateinit var empty: View
    private lateinit var adapter: RecentAdapter

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
            open(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        val openButton = findViewById<View>(R.id.open)
        val density = resources.displayMetrics.density

        applyBarInsets(findViewById(R.id.root), findViewById(R.id.status_spacer), findViewById(R.id.toolbar)) { bottom ->
            list.updatePadding(bottom = bottom + (96 * density).toInt())
            openButton.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = bottom + (16 * density).toInt()
            }
        }

        adapter = RecentAdapter()
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            adapter.getItem(position)?.let { open(Uri.parse(it.uri)) }
        }
        list.setOnItemLongClickListener { _, _, position, _ ->
            val item = adapter.getItem(position) ?: return@setOnItemLongClickListener true
            val pinLabel = getString(if (item.pinned) R.string.unpin else R.string.pin)
            MaterialAlertDialogBuilder(this)
                .setTitle(item.name)
                .setItems(arrayOf(pinLabel, getString(R.string.remove_from_list))) { _, which ->
                    if (which == 0) {
                        Recents.setPinned(this, item.uri, !item.pinned)
                    } else {
                        Recents.remove(this, item.uri)
                        Bookmarks.removeAll(this, item.uri)
                        try {
                            contentResolver.releasePersistableUriPermission(
                                Uri.parse(item.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        } catch (_: Exception) {
                        }
                    }
                    refresh()
                }
                .show()
            true
        }
        openButton.setOnClickListener { picker.launch(arrayOf("application/pdf")) }
    }

    override fun onStart() {
        super.onStart()
        refresh()
    }

    private fun refresh() {
        val items = Recents.load(this)
        adapter.clear()
        adapter.addAll(items)
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun open(uri: Uri) {
        startActivity(
            Intent(this, ViewerActivity::class.java)
                .setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    }

    private inner class RecentAdapter : ArrayAdapter<RecentFile>(this@MainActivity, R.layout.item_recent, R.id.name) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_recent, parent, false)
            val item = getItem(position) ?: return view
            view.findViewById<TextView>(R.id.name).text =
                if (item.pinned) getString(R.string.pinned_name, item.name) else item.name
            val date = DateFormat.getMediumDateFormat(context).format(Date(item.time))
            view.findViewById<TextView>(R.id.info).text =
                if (item.pages > 0) getString(R.string.recent_info, item.page + 1, item.pages, date) else date
            return view
        }
    }
}
