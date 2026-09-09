/*
 * Ported from Kototoro (Apache-2.0).
 * Copyright 2025 Kototoro contributors.
 * Copyright 2026 Kaisoku contributors.
 */
package org.koitharu.kotatsu.reader.ui.novel

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

data class NovelChapterData(
	val chapterIndex: Int,
	val content: String,
	val scroll: Int? = null,
)

class NovelContinuousAdapter(
	private var settings: NovelReaderSettings,
	private val onImageClick: (NovelInlineImageRequest) -> Unit,
	private val onTap: (chapterIndex: Int, viewTop: Float, eventTime: Long) -> Unit,
) : RecyclerView.Adapter<NovelContinuousAdapter.ChapterViewHolder>() {

	private val chapters = mutableListOf<NovelChapterData>()
	private var palette: NovelReaderPalette? = null

	class ChapterViewHolder(val view: NovelChapterView) : RecyclerView.ViewHolder(view) {

		fun bind(
			data: NovelChapterData,
			settings: NovelReaderSettings,
			palette: NovelReaderPalette?,
			onImageClick: (NovelInlineImageRequest) -> Unit,
			onTap: (chapterIndex: Int, viewTop: Float, eventTime: Long) -> Unit,
		) {
			view.updateSettings(settings)
			palette?.let(view::updatePalette)
			view.onImageClickListener = onImageClick
			view.onTapListener = { _, _, eventTime ->
				onTap(data.chapterIndex, view.top.toFloat(), eventTime)
			}
			view.setContent(data.content)
		}
	}

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChapterViewHolder {
		val view = NovelChapterView(parent.context).apply {
			layoutParams = RecyclerView.LayoutParams(
				RecyclerView.LayoutParams.MATCH_PARENT,
				RecyclerView.LayoutParams.WRAP_CONTENT,
			)
		}
		return ChapterViewHolder(view)
	}

	override fun onBindViewHolder(holder: ChapterViewHolder, position: Int) {
		holder.bind(chapters[position], settings, palette, onImageClick, onTap)
	}

	override fun getItemCount(): Int = chapters.size

	fun updateSettings(newSettings: NovelReaderSettings) {
		settings = newSettings
		notifyDataSetChanged()
	}

	fun updatePalette(newPalette: NovelReaderPalette) {
		palette = newPalette
		notifyDataSetChanged()
	}

	fun getItems(): List<NovelChapterData> = chapters.toList()

	fun setInitialChapter(data: NovelChapterData) {
		chapters.clear()
		chapters.add(data)
		notifyDataSetChanged()
	}

	fun prependChapter(data: NovelChapterData) {
		if (chapters.isNotEmpty() && chapters.first().chapterIndex == data.chapterIndex) return
		chapters.add(0, data)
		notifyItemInserted(0)
	}

	fun appendChapter(data: NovelChapterData) {
		if (chapters.isNotEmpty() && chapters.last().chapterIndex == data.chapterIndex) return
		chapters.add(data)
		notifyItemInserted(chapters.size - 1)
	}

	fun clear() {
		val size = chapters.size
		chapters.clear()
		notifyItemRangeRemoved(0, size)
	}
}
