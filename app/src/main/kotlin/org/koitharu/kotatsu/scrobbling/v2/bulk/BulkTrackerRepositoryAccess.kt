package org.koitharu.kotatsu.scrobbling.v2.bulk

import kotlinx.coroutines.flow.first
import org.koitharu.kotatsu.core.archive.ArchivePrivacy
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.favourites.domain.FavouritesRepository
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.scrobbling.common.data.withTrackerRequestGuard
import org.koitharu.kotatsu.scrobbling.v2.IncomingProgressLink
import org.koitharu.kotatsu.scrobbling.v2.TrackerBatchSelection
import org.koitharu.kotatsu.scrobbling.v2.TrackerCapability
import org.koitharu.kotatsu.scrobbling.v2.TrackerPatch
import org.koitharu.kotatsu.scrobbling.v2.TrackerReference
import org.koitharu.kotatsu.scrobbling.v2.TrackerRemoteEntry
import org.koitharu.kotatsu.scrobbling.v2.TrackerToolsRepository
import org.koitharu.kotatsu.search.domain.SearchKind
import org.koitharu.kotatsu.search.domain.SearchV2Helper
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class BulkTrackerRepositoryAccess @Inject constructor(
    private val tools: TrackerToolsRepository,
    private val sources: MangaSourcesRepository,
    private val searchFactory: SearchV2Helper.Factory,
    private val favorites: FavouritesRepository,
) : BulkTrackerAccess {
    override fun check(account: BulkTrackerAccount) = tools.checkConnection(account.connection())

    override suspend fun checkReadable(local: BulkTrackerLocal) {
        if (local.fromSearch) tools.checkSearchSource(local)
    }

    override suspend fun local(id: Long): BulkTrackerLocal? = tools.localManga(id)?.takeUnless {
        ArchivePrivacy.isSensitive(it)
    }?.let { snapshot(it, false) }

    override suspend fun link(account: BulkTrackerAccount, id: Long): BulkTrackerLink? {
        check(account)
        val confirmed = tools.incomingLink(account.connection(), id)
        val prior = tools.priorLink(account.connection(), id)
        return if (confirmed != null) BulkTrackerLink(confirmed.reference.mediaId, confirmed.reference.listEntryId,
            confirmed.revision, prior?.revision) else prior?.let {
            BulkTrackerLink(it.mediaId, it.listEntryId, "inactive-v2:${it.revision}", it.revision, false)
        }
    }

    override suspend fun search(account: BulkTrackerAccount, query: String): List<BulkTrackerCandidate> {
        check(account)
        return tools.provider(account.provider).search(account.connection(), query)
            .map(BulkTrackerCandidate::from).also { require(it.size <= 100); check(account) }
    }

    override suspend fun entry(account: BulkTrackerAccount, mediaId: String,
        beforeRequest: suspend () -> Unit): TrackerRemoteEntry? = withTrackerRequestGuard({ check(account); beforeRequest() }) {
        check(account)
        tools.incomingEntry(account.connection(), mediaId).also { check(account) }
    }

    override suspend fun createStatus(account: BulkTrackerAccount): String? {
        check(account)
        val provider = tools.provider(account.provider)
        if (TrackerCapability.STATUS_WRITE !in provider.capabilities) return null
        return when (account.provider) {
            "COMICK" -> "PLANNING".takeIf { "library:write" in account.scopes }
            "ANIME_PLANET" -> "PLANNING".takeIf { tools.canWriteAnimePlanet(account.connection()) }
            "ANILIST" -> "PLANNING"
            "MAL", "MANGABAKA" -> "plan_to_read"
            "KITSU", "SHIKIMORI" -> "planned"
            else -> null
        }
    }

    override suspend fun create(account: BulkTrackerAccount, mediaId: String, status: String,
        beforeSend: suspend () -> Unit) {
        require(createStatus(account) == status)
        withTrackerRequestGuard({ check(account); beforeSend() }) {
            // A list entry appearing after review is adopted without resetting its existing status.
            if (tools.incomingEntry(account.connection(), mediaId) != null) return@withTrackerRequestGuard
            tools.provider(account.provider).patch(account.connection(), mediaId, TrackerPatch.statusOnly(status), allowCreate = true)
        }
        check(account)
    }

    override suspend fun commit(account: BulkTrackerAccount, item: BulkTrackerItem, entry: TrackerRemoteEntry) {
        val local = requireNotNull(item.local)
        val selection = TrackerBatchSelection(local.id, requireNotNull(item.chosen).mediaId, confirmed = true,
            categoryId = item.category?.id, expectedLinkRevision = item.expectedLink?.v2Revision,
            expectedSource = local.source, expectedUrl = local.url)
        val expected = item.expectedLink?.takeIf { it.confirmed }?.let {
            IncomingProgressLink(TrackerReference(account.connection().owner, it.mediaId, it.entryId), it.revision)
        }
        tools.commitBulk(account.connection(), selection, entry, expected, local, item.category?.createdAt)
    }

    override suspend fun page(account: BulkTrackerAccount, cursor: String?): BulkTrackerPage {
        check(account)
        val page = tools.provider(account.provider).libraryPage(account.connection(), cursor)
        check(account)
        require(page.entries.all { it.reference.owner == account.connection().owner })
        return BulkTrackerPage(page.entries.map(BulkTrackerRemote::from), page.nextCursor)
    }

    suspend fun favorites(): List<BulkTrackerLocal> = tools.localFavorites().filterNot { ArchivePrivacy.isSensitive(it) }
        .also { require(it.size <= 20_000) { "Too many favorites for this chooser" } }.map { snapshot(it, false) }

    suspend fun categories(): List<BulkTrackerCategory> = favorites.observeCategories().first().filter { it.id > 0 }
        .map { BulkTrackerCategory(it.id, it.createdAt.toEpochMilli(), it.title) }

    suspend fun sourceNames(): List<String> = sources.getEnabledSources().map { it.name }.distinct().sorted()

    suspend fun searchSource(sourceName: String, query: String): List<BulkTrackerLocal> {
        require(query.trim().length in 2..100)
        val source = requireNotNull(sources.getEnabledSources().singleOrNull { it.name == sourceName }) {
            "This readable source is no longer enabled"
        }
        return searchFactory.create(source).invoke(query.trim(), SearchKind.SIMPLE)?.manga.orEmpty()
            .filterNot { ArchivePrivacy.isSensitive(it) }.also { require(it.size <= 300) }
            .map { snapshot(it, true) }
    }

    private fun snapshot(manga: Manga, fromSearch: Boolean): BulkTrackerLocal = BulkTrackerLocal(manga.id, manga.title,
        manga.altTitles, manga.source.name, manga.url, manga.publicUrl,
        format = if (manga.source.name.startsWith("lnreader:")) "NOVEL" else null,
        language = (manga.source as? MangaParserSource)?.locale?.toString(),
        contentRating = manga.contentRating?.name, fromSearch = fromSearch)
}
