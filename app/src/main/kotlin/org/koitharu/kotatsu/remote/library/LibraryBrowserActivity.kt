package org.koitharu.kotatsu.remote.library

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.appbar.MaterialToolbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.AppRouter
import javax.inject.Inject

/** Explicit native catalog UI. Opening it never scans the library or writes progress remotely. */
@AndroidEntryPoint
class LibraryBrowserActivity : AppCompatActivity() {
    @Inject lateinit var manager: RemoteLibraryManager
    private lateinit var status: TextView
    private lateinit var search: EditText
    private lateinit var searchButton: Button
    private lateinit var toolbar: MaterialToolbar
    private lateinit var list: RecyclerView
    private val rows = CatalogAdapter()
    private var connectionId: String? = null
    private var currentFeed: LibraryFeed? = null
    private var operation: Job? = null
    private var lastError: Throwable? = null
    private val navigation = ArrayList<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        connectionId = savedInstanceState?.getString(KEY_CONNECTION) ?: intent.getStringExtra(KEY_CONNECTION)
        navigation.addAll(savedInstanceState?.getStringArrayList(KEY_NAVIGATION).orEmpty())
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        toolbar = MaterialToolbar(this).apply {
            title = getString(R.string.remote_library_title)
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
            menu.add(getString(R.string.remote_library_add)).setOnMenuItemClickListener { connectionDialog(); true }
            menu.add(getString(R.string.remote_library_refresh)).setOnMenuItemClickListener { load(true); true }
            menu.add(getString(R.string.remote_library_edit)).setOnMenuItemClickListener {
                connectionId?.let { id -> launchAction { manager.connection(id)?.let(::connectionDialog) } }; true
            }
        }
        root.addView(toolbar)
        status = TextView(this).apply {
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnClickListener { lastError?.let(::showError) }
        }
        root.addView(status)
        val searchRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), 0, dp(12), 0) }
        search = EditText(this).apply { hint = getString(R.string.remote_library_search); maxLines = 1; inputType = InputType.TYPE_CLASS_TEXT }
        searchButton = Button(this).apply {
            text = getString(android.R.string.search_go)
            setOnClickListener {
                val feed = currentFeed ?: return@setOnClickListener
                val query = search.text.toString().trim()
                if (query.isNotEmpty()) launchAction {
                    val result = session().search(feed, query)
                    navigation.add(result.feed.documentUrl)
                    render(result)
                }
            }
        }
        searchRow.addView(search, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        searchRow.addView(searchButton)
        root.addView(searchRow)
        list = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@LibraryBrowserActivity); adapter = rows }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    navigation.size > 1 -> { navigation.removeAt(navigation.lastIndex); load(false) }
                    connectionId != null -> { connectionId = null; navigation.clear(); load(false) }
                    else -> finish()
                }
            }
        })
    }

    override fun onStart() { super.onStart(); load(false) }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(KEY_CONNECTION, connectionId)
        outState.putStringArrayList(KEY_NAVIGATION, navigation)
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        operation?.cancel()
        rows.cancelCovers()
        super.onStop()
    }

    private fun load(refresh: Boolean): Unit = launchAction {
        if (connectionId == null) {
            currentFeed = null
            toolbar.title = getString(R.string.remote_library_title)
            status.text = getString(R.string.remote_library_intro)
            lastError = null
            search.visibility = View.GONE
            searchButton.visibility = View.GONE
            rows.replace(manager.connections().map { connection -> Row.LinkRow(connection.name + if (connection.enabled) "" else " · disabled", {
                if (!connection.enabled) connectionDialog(connection) else {
                    connectionId = connection.id
                    navigation.clear()
                    load(false)
                }
            }, { connectionDialog(connection) }) })
        } else {
            val active = session()
            val url = navigation.lastOrNull() ?: active.connection.catalogUrl.also { navigation.add(it) }
            render(active.feed(url, refresh))
        }
    }

    private fun render(result: LibraryFeedResult) {
        currentFeed = result.feed
        lastError = result.refreshError
        toolbar.title = result.feed.title
        status.text = when {
            result.refreshError != null -> getString(R.string.remote_library_warning)
            result.fromCache -> getString(R.string.remote_library_cached)
            else -> ""
        }
        status.contentDescription = status.text
        search.visibility = if (result.feed.search != null) View.VISIBLE else View.GONE
        searchButton.visibility = search.visibility
        val items = ArrayList<Row>()
        fun navigation(link: LibraryLink) {
            if (link.isCatalog && !link.templated) items += Row.LinkRow(link.title ?: link.href, { follow(link) })
        }
        result.feed.navigation.forEach(::navigation)
        result.feed.facets.forEach { facet ->
            items += Row.Heading(facet.title)
            facet.links.forEach(::navigation)
        }
        result.feed.publications.forEach { items += Row.Book(it) }
        result.feed.groups.forEach { group ->
            items += Row.Heading(group.title)
            group.navigation.forEach(::navigation)
            group.publications.forEach { items += Row.Book(it) }
            group.links.filter { it.isCatalog && !it.hasRel("self") }.forEach(::navigation)
        }
        result.feed.next?.let { next -> items += Row.LinkRow(getString(R.string.remote_library_next), {
            launchAction {
                val nextPage = session().next(result.feed, navigation.toSet()) ?: return@launchAction
                navigation.add(nextPage.feed.documentUrl)
                render(nextPage)
                list.scrollToPosition(0)
            }
        }) }
        if (items.isEmpty()) items += Row.Heading(getString(R.string.remote_library_empty))
        rows.replace(items)
    }

    private fun follow(link: LibraryLink) {
        launchAction {
            val feed = currentFeed ?: return@launchAction
            val target = session().transport.policy.resolve(feed.documentUrl, link.href).toString()
            if (target in navigation) throw RemoteLibraryException("This catalog link points to a page already in the navigation stack")
            val result = session().feed(target)
            navigation.add(result.feed.documentUrl)
            render(result)
            list.scrollToPosition(0)
        }
    }

    private fun showPublication(publication: LibraryPublication): Unit = launchAction {
        val active = session()
        active.permitted(publication)
        val actions = ArrayList<Pair<String, () -> Unit>>()
        if (publication.pageStream != null || publication.readingOrder.isNotEmpty() || publication.manifestLink != null) {
            actions += getString(R.string.remote_library_read) to {
                launchAction {
                    // Validate the advertised stream/manifest before opening an unsupported reader.
                    active.pages(publication)
                    val source = RemoteLibrarySource(active.connection.id, active.connection.name, active.connection.content)
                    startActivity(AppRouter.detailsIntent(this@LibraryBrowserActivity,
                        RemoteLibraryMangaRepository(source, active).toManga(publication)))
                }
            }
        }
        publication.acquisitionLinks.forEach { link ->
            actions += getString(R.string.remote_library_acquire, link.supportedFormat!!.name) to { confirmAcquisition(publication, link) }
        }
        manager.acquired(active.connection.id, publication.identifier).forEach { record ->
            actions += getString(R.string.remote_library_open_local) to { launchAction {
                startActivity(AppRouter.detailsIntent(this@LibraryBrowserActivity, manager.openAcquired(record)))
            } }
        }
        actions += getString(R.string.remote_library_details) to { launchAction {
            showPublication(active.publication(publication.identifier, true))
        } }
        val description = buildString {
            if (publication.authors.isNotEmpty()) append(publication.authors.joinToString()).append("\n\n")
            if (publication.languages.isNotEmpty()) append(publication.languages.joinToString()).append("\n\n")
            append(publication.description.orEmpty())
            if (actions.size == 1) append("\n\n").append(getString(R.string.remote_library_unsupported))
        }
        val box = LinearLayout(this@LibraryBrowserActivity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), dp(8))
            addView(TextView(context).apply { text = description; setTextIsSelectable(true) })
            actions.forEach { (label, action) -> addView(Button(context).apply { text = label; setOnClickListener { action() } }) }
        }
        MaterialAlertDialogBuilder(this@LibraryBrowserActivity).setTitle(publication.title)
            .setView(ScrollView(this@LibraryBrowserActivity).apply { addView(box) })
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun confirmAcquisition(publication: LibraryPublication, link: LibraryLink) {
        val target = link.href.toHttpUrlOrNull() ?: return showError(RemoteLibraryException("Invalid acquisition link"))
        if (session().transport.policy.contains(target)) acquire(publication, link, false) else {
            MaterialAlertDialogBuilder(this).setTitle(R.string.remote_library_anonymous_title)
                .setMessage(getString(R.string.remote_library_anonymous_message, "${target.scheme}://${target.host}:${target.port}"))
                .setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok) { _, _ -> acquire(publication, link, true) }.show()
        }
    }

    private fun acquire(publication: LibraryPublication, link: LibraryLink, anonymous: Boolean) {
        operation?.cancel()
        val progress = TextView(this).apply { text = getString(R.string.remote_library_downloading); setPadding(dp(20), dp(12), dp(20), dp(12)) }
        val dialog = MaterialAlertDialogBuilder(this).setTitle(publication.title).setView(progress)
            .setNegativeButton(R.string.remote_library_stop) { _, _ -> operation?.cancel() }.create()
        dialog.setOnCancelListener { operation?.cancel() }
        dialog.show()
        operation = lifecycleScope.launch {
            try {
                var last = 0L
                val result = manager.acquire(connectionId ?: throw RemoteLibraryException("Library connection changed"), publication.identifier, link, anonymous) { bytes, _ ->
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - last >= 250L) { last = now; runOnUiThread { progress.text = getString(R.string.remote_library_progress, bytes / 1024) } }
                }
                status.text = getString(R.string.remote_library_acquired)
                startActivity(AppRouter.detailsIntent(this@LibraryBrowserActivity, result.localManga.manga))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { showError(error) }
            finally { dialog.dismiss() }
        }
    }

    private fun connectionDialog(existing: RemoteLibraryConnection? = null) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        fun field(hint: Int, value: String = "", password: Boolean = false) = EditText(this).apply {
            setHint(hint); setText(value); maxLines = 1; isSaveEnabled = !password
            inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT
            box.addView(this)
        }
        val name = field(R.string.remote_library_name, existing?.name.orEmpty())
        val url = field(R.string.remote_library_url, existing?.catalogUrl.orEmpty()).apply { isEnabled = existing == null }
        val scope = field(R.string.remote_library_scope, existing?.scopeUrl.orEmpty()).apply { isEnabled = existing == null }
        val kind = Spinner(this).apply {
            adapter = ArrayAdapter(this@LibraryBrowserActivity, android.R.layout.simple_spinner_dropdown_item, RemoteLibraryKind.entries.map { it.name })
            setSelection(existing?.kind?.ordinal ?: 0); isEnabled = existing == null
        }
        box.addView(kind)
        val account = field(R.string.remote_library_username, existing?.account.orEmpty()).apply { isEnabled = existing == null }
        val password = field(if (existing == null) R.string.remote_library_password else R.string.remote_library_password_keep, password = true)
        val content = Spinner(this).apply {
            adapter = ArrayAdapter(this@LibraryBrowserActivity, android.R.layout.simple_spinner_dropdown_item, listOf(
                getString(R.string.remote_library_unknown), getString(R.string.remote_library_safe),
                getString(R.string.remote_library_mixed), getString(R.string.remote_library_adult)))
            setSelection(existing?.content?.ordinal ?: 0)
        }
        box.addView(content)
        val enabled = CheckBox(this).apply { setText(R.string.remote_library_enabled); isChecked = existing?.enabled ?: true }
        box.addView(enabled)
        val cleartext = CheckBox(this).apply { setText(R.string.remote_library_cleartext); isChecked = existing?.allowCleartextLan ?: false }
        box.addView(cleartext)
        box.addView(TextView(this).apply { setText(if (existing == null) R.string.remote_library_scope_help else R.string.remote_library_server_identity) })
        val dialog = MaterialAlertDialogBuilder(this).setTitle(if (existing == null) R.string.remote_library_add else R.string.remote_library_edit)
            .setView(ScrollView(this).apply { addView(box) }).setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.remote_library_save, null).create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                launchAction {
                    val selected = RemoteLibraryContent.entries[content.selectedItemPosition]
                    val value = existing?.copy(name = name.text.toString().trim(), content = selected, enabled = enabled.isChecked,
                        allowCleartextLan = cleartext.isChecked)
                        ?: RemoteLibraryConnection.create(name.text.toString(), url.text.toString().trim(),
                            RemoteLibraryKind.entries[kind.selectedItemPosition], account.text.toString(), selected, cleartext.isChecked).let {
                                it.copy(scopeUrl = scope.text.toString().trim().takeIf(String::isNotEmpty) ?: it.scopeUrl, enabled = enabled.isChecked)
                            }
                    val secret = password.text.toString().takeIf { it.isNotEmpty() || existing == null }
                    fun save() = launchAction {
                        manager.save(value, secret)
                        password.text.clear()
                        dialog.dismiss()
                        connectionId = value.id.takeIf { value.enabled }
                        navigation.clear()
                        load(false)
                    }
                    if (value.catalogUrl.startsWith("http:")) MaterialAlertDialogBuilder(this@LibraryBrowserActivity)
                        .setTitle(R.string.remote_library_cleartext).setMessage(R.string.remote_library_cleartext_warning)
                        .setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok) { _, _ -> save() }.show()
                    else save()
                }
            }
        }
        dialog.setOnDismissListener { password.text.clear() }
        dialog.show()
    }

    private fun session(): RemoteLibrarySession = manager.session(connectionId ?: throw RemoteLibraryException("Choose a library connection"))

    private fun launchAction(block: suspend () -> Unit) {
        operation?.cancel()
        operation = lifecycleScope.launch {
            try { block() } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { lastError = error; status.text = getString(R.string.remote_library_warning); showError(error) }
        }
    }

    private fun showError(error: Throwable) {
        if (isFinishing || isDestroyed) return
        val details = LibraryDiagnostics.text(error)
        MaterialAlertDialogBuilder(this).setTitle(R.string.remote_library_error).setMessage(LibraryDiagnostics.message(error))
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.remote_library_copy_error) { _, _ ->
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(getString(R.string.remote_library_error), details))
            }.show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private sealed class Row {
        data class Heading(val title: String) : Row()
        data class LinkRow(val title: String, val action: () -> Unit, val longAction: (() -> Unit)? = null) : Row()
        data class Book(val publication: LibraryPublication) : Row()
    }

    private inner class CatalogAdapter : RecyclerView.Adapter<CatalogHolder>() {
        private var items: List<Row> = emptyList()
        private val holders = HashSet<CatalogHolder>()
        fun cancelCovers() { holders.forEach { it.coverJob?.cancel() } }
        fun replace(value: List<Row>) { items = value; notifyDataSetChanged() }
        override fun getItemCount(): Int = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CatalogHolder {
            val container = LinearLayout(parent.context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(10), dp(16), dp(10)) }
            val image = ImageView(parent.context).apply { scaleType = ImageView.ScaleType.CENTER_INSIDE }
            container.addView(image, LinearLayout.LayoutParams(dp(48), dp(72)))
            val title = TextView(parent.context).apply { textSize = 17f; setPadding(dp(12), 0, 0, 0) }
            container.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            return CatalogHolder(container, image, title).also { holders.add(it) }
        }
        override fun onBindViewHolder(holder: CatalogHolder, position: Int) {
            holder.coverJob?.cancel(); holder.coverJob = null
            holder.image.setImageDrawable(null)
            holder.image.visibility = View.GONE
            holder.itemView.setOnLongClickListener(null)
            when (val row = items[position]) {
                is Row.Heading -> { holder.title.text = row.title; holder.itemView.setOnClickListener(null) }
                is Row.LinkRow -> {
                    holder.title.text = row.title
                    holder.itemView.setOnClickListener { row.action() }
                    row.longAction?.let { action -> holder.itemView.setOnLongClickListener { action(); true } }
                }
                is Row.Book -> {
                    holder.title.text = row.publication.title + row.publication.authors.takeIf { it.isNotEmpty() }?.joinToString(prefix = "\n").orEmpty()
                    holder.itemView.setOnClickListener { showPublication(row.publication) }
                    row.publication.cover?.let { cover ->
                        holder.image.visibility = View.VISIBLE
                        holder.coverJob = lifecycleScope.launch {
                            try {
                                val active = session()
                                active.permitted(row.publication)
                                val url = active.transport.policy.resolve(row.publication.documentUrl, cover.href).toString()
                                val bitmap = withContext(Dispatchers.IO) {
                                    val bytes = active.transport.document(url).bytes
                                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth.toLong() * bounds.outHeight > 100_000_000L) null else {
                                        val sample = generateSequence(1) { it * 2 }.first { bounds.outWidth / it <= 160 && bounds.outHeight / it <= 240 }
                                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                                    }
                                }
                                holder.image.setImageBitmap(bitmap)
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { /* Missing covers do not hide books or stop catalog browsing. */ }
                        }
                    }
                }
            }
        }
        override fun onViewRecycled(holder: CatalogHolder) { holder.coverJob?.cancel(); holder.image.setImageDrawable(null) }
        override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) { cancelCovers(); holders.clear() }
    }

    private class CatalogHolder(view: View, val image: ImageView, val title: TextView) : RecyclerView.ViewHolder(view) { var coverJob: Job? = null }

    companion object {
        private const val KEY_CONNECTION = "remote_library_connection"
        private const val KEY_NAVIGATION = "remote_library_navigation"
        fun intent(context: Context, connectionId: String? = null): Intent = Intent(context, LibraryBrowserActivity::class.java)
            .apply { connectionId?.let { putExtra(KEY_CONNECTION, it) } }
    }
}
