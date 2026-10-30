package org.koitharu.kotatsu.search.unified

import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.model.parcelable.ParcelableManga
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.parser.ChapterTextRepository
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.util.ext.getParcelableExtraCompat
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.scrobbling.v2.bulk.*
import org.koitharu.kotatsu.search.domain.*
import java.util.UUID
import javax.inject.Inject

fun showTrackerSearchCard(activity: FragmentActivity, card: TrackerSearchCard,
    sources: ReadingSourceScope = ReadingSourceScope(), local: Manga? = null) {
    if (local != null && org.koitharu.kotatsu.core.archive.ArchivePrivacy.isSensitive(local)) {
        showTrackerSearchError(activity, IllegalStateException("Use the existing protected-title tracking controls")); return
    }
    if (local == null && card.editions.size == 1) { activity.router.openDetails(card.editions.single()); return }
    if (local == null && card.editions.size > 1) {
        MaterialAlertDialogBuilder(activity).setTitle(R.string.choose_source)
            .setItems(card.editions.map { it.source.getTitle(activity) }.toTypedArray()) { _, i -> activity.router.openDetails(card.editions[i]) }
            .show(); return
    }
    TrackerSearchLinkSheet().apply {
        arguments = Bundle().apply {
            putString("request", SearchRequest(card.work.title, sources = sources, tracker = card.account, trackerTarget = card.work.id).encode())
            putString("work", Json.encodeToString(CatalogueTitle.serializer(), card.work))
            putString("draft", UUID.randomUUID().toString())
            local?.let { putParcelable("local", ParcelableManga(it, withDescription = false)) }
        }
    }.show(activity.supportFragmentManager, "tracker-search-link")
}

/** Search selection uses the existing durable tracker journal; recreation never resends a write. */
@AndroidEntryPoint
class TrackerSearchLinkSheet : DialogFragment() {
    @Inject lateinit var repository: TrackerSearchRepository
    @Inject lateinit var engine: BulkTrackerEngine
    @Inject lateinit var factory: MangaRepository.Factory
    @Inject lateinit var settings: org.koitharu.kotatsu.core.prefs.AppSettings
    private val request get() = requireNotNull(SearchRequest.decode(requireArguments().getString("request")))
    private val work get() = Json.decodeFromString(CatalogueTitle.serializer(), requireArguments().getString("work")!!)
    private var selected: Manga? = null
    private var job: Job? = null
    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val manga = result.data?.getParcelableExtraCompat<ParcelableManga>(AppRouter.KEY_MANGA)?.manga
        if (result.resultCode == android.app.Activity.RESULT_OK && manga != null) {
            selected = manga
            requireArguments().putParcelable("local", ParcelableManga(manga, withDescription = false))
            updateDialog()
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selected = requireArguments().getParcelable<ParcelableManga>("local")?.manga
    }
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog = MaterialAlertDialogBuilder(requireContext())
        .setTitle(R.string.tracker_search_link).setMessage(work.title)
        .setPositiveButton(R.string.search_sources_for_title, null).setNegativeButton(android.R.string.cancel, null).create()
    override fun onStart() { super.onStart(); updateDialog() }

    private fun updateDialog() {
        val dialog = dialog as? androidx.appcompat.app.AlertDialog ?: return
        val manga = selected
        dialog.setMessage(if (manga == null) work.title else getString(R.string.tracker_search_confirm,
            manga.title, manga.source.getTitle(requireContext()), work.title))
        val button = dialog.getButton(Dialog.BUTTON_POSITIVE)
        button.setText(if (manga == null) R.string.search_sources_for_title else R.string.tracker_link)
        button.setOnClickListener {
            if (job?.isActive == true) return@setOnClickListener
            if (selected == null) {
                picker.launch(AppRouter.pickMangaIntent(requireContext(), request.query)
                    .putExtra(SearchRequest.EXTRA, request.encode()))
            } else apply(false)
        }
    }

    private fun apply(allowCreate: Boolean) {
        val manga = selected ?: return
        val account = request.tracker ?: return
        val button = (dialog as? androidx.appcompat.app.AlertDialog)?.getButton(Dialog.BUTTON_POSITIVE)
        button?.isEnabled = false
        job = lifecycleScope.launch {
            try {
                val missing = withContext(Dispatchers.IO) {
                    val connection = repository.verified(account)
                    check(settings.sourceContentPolicy.allows(manga)) { "The selected source title is now restricted" }
                    val actualNovel = factory.create(manga.source) is ChapterTextRepository
                    require(work.isNovel == null || work.isNovel == actualNovel) { "The source and tracker adaptations differ" }
                    if (request.sources.kind == SourceScopeKind.SINGLE)
                        require(manga.source.name == request.sources.sourceName) { "The selected reading source changed" }
                    val owner = BulkTrackerAccount.from(connection)
                    val candidate = BulkTrackerCandidate(work.id, work.title, work.aliases.take(32).toSet(), work.url, work.format)
                    val local = BulkTrackerLocal(manga.id, manga.title, manga.altTitles.take(32).toSet(), manga.source.name,
                        manga.url, manga.publicUrl, contentRating = manga.contentRating?.name, fromSearch = true)
                    val draft = engine.prepareSearchLink(owner, local, candidate, requireArguments().getString("draft")!!)
                    val item = draft.items.single()
                    if (item.uncertain) {
                        engine.check(draft.id, item.id)
                        check(engine.draft(draft.id).items.single().state == BulkTrackerItemState.APPLIED) {
                            getString(R.string.tracker_search_pending)
                        }
                        false
                    } else if (!allowCreate && repository.tools.provider(account.provider).entry(connection, work.id) == null) true
                    else {
                        engine.choose(draft.id, item.id, candidate, local, null, allowCreate)
                        engine.apply(draft.id)
                        check(engine.draft(draft.id).items.single().state == BulkTrackerItemState.APPLIED) {
                            engine.draft(draft.id).items.single().error ?: getString(R.string.tracker_search_pending)
                        }
                        false
                    }
                }
                if (missing) MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.tracker_create_title)
                    .setMessage(getString(R.string.tracker_create_confirmation, work.title))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.tracker_link) { _, _ -> apply(true) }.show()
                else dismiss()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { showTrackerSearchError(requireContext(), e)
            } finally { button?.isEnabled = true }
        }
    }
}
