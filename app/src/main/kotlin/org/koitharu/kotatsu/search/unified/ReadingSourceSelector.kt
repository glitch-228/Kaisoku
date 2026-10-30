package org.koitharu.kotatsu.search.unified

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.search.domain.ReadingSourceScope
import org.koitharu.kotatsu.search.domain.SourceScopeKind

fun ReadingSourceScope.label(context: Context, sources: List<MangaSource>): String = when (kind) {
    SourceScopeKind.ENABLED -> context.getString(R.string.enabled_sources)
    SourceScopeKind.PINNED -> context.getString(R.string.pinned_sources_only)
    SourceScopeKind.SINGLE -> sources.firstOrNull { it.name == sourceName }?.getTitle(context) ?: sourceName.orEmpty()
}

fun showReadingSourceSelector(context: Context, sources: List<MangaSource>, onSelected: (ReadingSourceScope) -> Unit) {
    val choices = listOf(ReadingSourceScope(), ReadingSourceScope(SourceScopeKind.PINNED)) +
        sources.distinctBy { it.name }.sortedBy { it.getTitle(context).lowercase() }
            .map { ReadingSourceScope(SourceScopeKind.SINGLE, it.name) }
    var visible = choices
    val search = EditText(context).apply { hint = context.getString(R.string.search); isSingleLine = true }
    val list = ListView(context)
    val adapter = ArrayAdapter<String>(context, android.R.layout.simple_list_item_1)
    fun update(query: String) {
        visible = choices.filter { it.label(context, sources).contains(query, true) }
        adapter.clear(); adapter.addAll(visible.map { it.label(context, sources) }); adapter.notifyDataSetChanged()
    }
    list.adapter = adapter
    val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(search, LinearLayout.LayoutParams(-1, -2))
        addView(list, LinearLayout.LayoutParams(-1, (context.resources.displayMetrics.density * 360).toInt()))
    }
    val dialog = MaterialAlertDialogBuilder(context).setTitle(R.string.choose_source).setView(content)
        .setNegativeButton(android.R.string.cancel, null).create()
    list.setOnItemClickListener { _, _, position, _ -> onSelected(visible[position]); dialog.dismiss() }
    search.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = update(s?.toString().orEmpty())
        override fun afterTextChanged(s: Editable?) = Unit
    })
    update(""); dialog.show()
}
