/*
 * Ported from Kototoro (Apache-2.0).
 * Copyright 2025 Kototoro contributors.
 * Copyright 2026 Kaisoku contributors.
 */
package org.koitharu.kotatsu.reader.ui.novel

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.flow.combine
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.databinding.ItemNovelChapterBinding
import org.koitharu.kotatsu.databinding.SheetNovelChaptersBinding
import org.koitharu.kotatsu.parsers.model.MangaChapter

class NovelChaptersSheet : BottomSheetDialogFragment() {

	private var _binding: SheetNovelChaptersBinding? = null
	private val binding get() = _binding!!

	private var chapters: List<MangaChapter> = emptyList()
	private var currentIndex: Int = 0
	private var isReversed: Boolean = false
	private var callback: Callback? = null
	private val viewModel by activityViewModels<NovelReaderViewModel>()

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View {
		_binding = SheetNovelChaptersBinding.inflate(inflater, container, false)
		return binding.root
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		callback = activity as? Callback

		isReversed = savedInstanceState?.getBoolean("reverseList") ?: false
		binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
		combine(viewModel.chapters, viewModel.currentChapterIndex, viewModel.isReadingReversed) { list, index, reversed ->
			Triple(list, index, reversed)
		}.observe(viewLifecycleOwner) { (list, index, reversed) ->
			chapters = list
			currentIndex = index
			binding.textChapterCount.text = getString(R.string.chapters_count, list.size)
			updateAdapter()
			scrollToCurrentChapter()
		}

		binding.buttonReverse.setOnClickListener {
			isReversed = !isReversed
			updateAdapter()
		}

	}

	override fun onSaveInstanceState(outState: Bundle) {
		outState.putBoolean("reverseList", isReversed)
		super.onSaveInstanceState(outState)
	}

	override fun onDestroyView() {
		super.onDestroyView()
		_binding = null
		callback = null
	}

	private fun updateAdapter() {
		val displayChapters = if (isReversed) chapters.reversed() else chapters

		binding.recyclerView.adapter = ChaptersAdapter(
			displayChapters,
			if (isReversed) chapters.size - 1 - currentIndex else currentIndex,
		) { position ->
			val actualIndex = if (isReversed) {
				chapters.size - 1 - position
			} else {
				position
			}
			callback?.onChapterSelected(actualIndex)
			dismiss()
		}
	}

	private fun scrollToCurrentChapter() {
		val adapter = binding.recyclerView.adapter as? ChaptersAdapter ?: return
		val targetChapterIndex = if (isReversed) {
			chapters.size - 1 - currentIndex
		} else {
			currentIndex
		}
		if (targetChapterIndex >= 0) {
			binding.recyclerView.smoothScrollToPosition(targetChapterIndex)
		}
	}

	interface Callback {

		fun onChapterSelected(index: Int)
	}

	private class ChaptersAdapter(
		private val chapters: List<MangaChapter>,
		private val currentIndex: Int,
		private val onChapterClick: (Int) -> Unit,
	) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

		override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
			val binding = ItemNovelChapterBinding.inflate(
				LayoutInflater.from(parent.context),
				parent,
				false,
			)
			return ChapterViewHolder(binding)
		}

		override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
			val chapterHolder = holder as ChapterViewHolder
			val isCurrent = position == currentIndex
			chapterHolder.binding.textTitle.text = chapters[position].name
			chapterHolder.binding.indicator.visibility = if (isCurrent) View.VISIBLE else View.INVISIBLE
			chapterHolder.binding.root.alpha = if (isCurrent) 1.0f else 0.7f
			chapterHolder.binding.root.setOnClickListener {
				onChapterClick(position)
			}
		}

		override fun getItemCount(): Int = chapters.size

		class ChapterViewHolder(val binding: ItemNovelChapterBinding) :
			RecyclerView.ViewHolder(binding.root)
	}
}
