package org.koitharu.kotatsu.scrobbling.v2.bulk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.scrobbling.common.data.TrackerHttpException
import org.koitharu.kotatsu.scrobbling.v2.BulkTrackerMatchPolicy
import org.koitharu.kotatsu.scrobbling.v2.TrackerProviderError
import org.koitharu.kotatsu.scrobbling.v2.TrackerRemoteEntry
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** All network work is explicitly UI initiated. Resume/recreation only loads a private journal. */
@Singleton
class BulkTrackerEngine internal constructor(
    private val store: BulkTrackerStore,
    private val access: BulkTrackerAccess,
    private val now: () -> Long,
) {
    @Inject internal constructor(store: BulkTrackerStore, access: BulkTrackerRepositoryAccess) :
        this(store, access, System::currentTimeMillis)

    private val operations = Mutex()
    val changes get() = store.changes

    fun drafts(account: BulkTrackerAccount): List<BulkTrackerDraft> = store.current().drafts.filter {
        it.account.sameOwner(account)
    }.sortedByDescending { it.createdAt }

    fun draft(id: String): BulkTrackerDraft = requireNotNull(store.current().drafts.singleOrNull { it.id == id }) {
        "This draft is unavailable"
    }

    fun library(account: BulkTrackerAccount): BulkTrackerLibrary? = store.current().libraries.singleOrNull {
        it.account.sameOwner(account)
    }

    suspend fun match(account: BulkTrackerAccount, locals: List<BulkTrackerLocal>, requestId: String? = null): BulkTrackerDraft =
        operations.withLock {
        access.check(account)
        require(locals.size in 1..100 && locals.map { it.id }.distinct().size == locals.size) {
            "Select between one and 100 distinct titles"
        }
        if (requestId != null) store.current().drafts.singleOrNull { it.id == requestId }?.let { existing ->
            check(existing.account.sameOwner(account) && existing.direction == BulkTrackerDirection.MATCH_FAVORITES &&
                existing.items.size == locals.size && existing.items.all { item ->
                    item.local?.let { selected -> locals.any(selected::sameEdition) } == true
                }) { "This saved selection belongs to another account or source edition" }
            return@withLock existing
        }
        val items = locals.map { local ->
            val actual = requireNotNull(access.local(local.id)) { "A selected local title is unavailable" }
            check(local.sameEdition(actual)) { "A selected source edition changed" }
            val link = access.link(account, local.id)
            BulkTrackerItem(uuid(), local, expectedLink = link,
                state = if (link?.confirmed == true) BulkTrackerItemState.RETAINED else BulkTrackerItemState.UNRESOLVED)
        }
        access.check(account)
        BulkTrackerDraft(requestId ?: uuid(), account, BulkTrackerDirection.MATCH_FAVORITES, now(), items).also { result ->
            store.mutate { it.copy(drafts = it.drafts + result) }
        }
    }

    suspend fun importList(account: BulkTrackerAccount, entries: List<BulkTrackerRemote>): BulkTrackerDraft = operations.withLock {
        access.check(account)
        require(entries.size in 1..100 && entries.map { it.candidate.mediaId }.distinct().size == entries.size)
        val cached = requireNotNull(library(account))
        check(cached.account == account && entries.all { it in cached.entries }) { "Reload this account's list first" }
        BulkTrackerDraft(uuid(), account, BulkTrackerDirection.IMPORT_LIST, now(), entries.map { entry ->
            BulkTrackerItem(uuid(), null, entry, listOf(entry.candidate), entry.candidate)
        }).also { result -> store.mutate { it.copy(drafts = it.drafts + result) } }
    }

    suspend fun loadLibrary(account: BulkTrackerAccount, append: Boolean): Unit = operations.withLock {
        access.check(account)
        val previous = library(account)
        if (append) check(previous != null && previous.account == account && previous.loaded && !previous.complete) {
            "Reload this account's list before continuing"
        }
        try {
            val cursor = if (append) previous?.nextCursor else null
            val page = access.page(account, cursor)
            access.check(account)
            check(page.nextCursor == null || page.nextCursor != cursor) { "Tracker pagination did not advance" }
            val entries = ((if (append) previous?.entries.orEmpty() else emptyList()) + page.entries)
                .associateBy { it.candidate.mediaId }.values.toList()
            val library = BulkTrackerLibrary(account, entries, page.nextCursor, loaded = true,
                complete = page.nextCursor == null)
            store.mutate { old -> old.copy(libraries = old.libraries.filterNot { it.account.sameOwner(account) } + library) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val preserved = previous ?: BulkTrackerLibrary(account)
            store.mutate { old -> old.copy(libraries = old.libraries.filterNot { it.account.sameOwner(account) } +
                preserved.copy(error = diagnostic(e))) }
            throw e
        }
        Unit
    }

    suspend fun search(id: String, onlyItem: String? = null) = operations.withLock {
        val original = draft(id)
        access.check(original.account)
        check(original.retryAt <= now()) { "The provider cooldown has not elapsed" }
        for (item in original.items) {
            currentCoroutineContext().ensureActive()
            access.check(original.account)
            if (onlyItem != null && onlyItem != item.id) continue
            if (item.local == null || item.uncertain || item.state in setOf(BulkTrackerItemState.APPLIED,
                    BulkTrackerItemState.READY_TO_LINK, BulkTrackerItemState.CONFIRMED) ||
                onlyItem == null && item.state == BulkTrackerItemState.RETAINED) continue
            val candidates = item.candidates.toMutableList()
            try {
                validate(original, item)
                val queries = (listOf(item.local.title) + item.local.aliases.sorted()).map { it.trim().take(100) }
                    .filter { it.length >= 2 }.distinct().take(3)
                for (query in queries) {
                    val found = access.search(original.account, query)
                    access.check(original.account)
                    candidates += found
                    val distinct = BulkTrackerMatchPolicy.propose(item.local.aliases + item.local.title,
                        candidates.distinctBy { it.mediaId }.map { it.title() }, expectedFormat = item.local.format)
                        .candidates.map(BulkTrackerCandidate::from)
                    require(distinct.size <= 300)
                    update(id, item.id) { it.copy(candidates = distinct, state = BulkTrackerItemState.SUGGESTED,
                        chosen = null, allowCreate = false, error = null) }
                }
                if (queries.isEmpty() || draft(id).items.single { it.id == item.id }.candidates.isEmpty()) update(id, item.id) {
                    it.copy(state = BulkTrackerItemState.UNRESOLVED, error = "No candidate was found; search or choose a different title")
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                update(id, item.id) { it.copy(state = BulkTrackerItemState.FAILED, error = diagnostic(e)) }
                if (stopsPass(e)) { failDraft(id, e); break }
            }
        }
    }

    /** Both local source selection and title matching remain suggestions until this explicit per-item confirmation. */
    suspend fun choose(id: String, itemId: String, candidate: BulkTrackerCandidate, local: BulkTrackerLocal?,
        category: BulkTrackerCategory?, allowCreate: Boolean): Unit = operations.withLock {
        val draft = draft(id)
        val item = draft.items.single { it.id == itemId }
        access.check(draft.account)
        check(!item.uncertain && item.state !in setOf(BulkTrackerItemState.APPLIED, BulkTrackerItemState.READY_TO_LINK)) {
            "Check the previous remote addition before changing this choice"
        }
        require(candidate in item.candidates)
        val chosenLocal = requireNotNull(local ?: item.local) { "Choose a real readable source result first" }
        require(candidate.format == null || chosenLocal.format == null || candidate.format.equals(chosenLocal.format, true)) {
            "The tracker adaptation and readable source format differ"
        }
        if (draft.direction == BulkTrackerDirection.IMPORT_LIST) require(category != null)
        require(draft.items.none { it.id != itemId && it.local?.id == chosenLocal.id }) {
            "This local edition is already selected by another draft item"
        }
        val actual = access.local(chosenLocal.id)
        check(actual?.sameEdition(chosenLocal) ?: chosenLocal.fromSearch) { "Selected source edition changed" }
        val link = access.link(draft.account, chosenLocal.id)
        if (allowCreate) check(access.createStatus(draft.account) != null) { "This provider lane cannot add remote entries" }
        update(id, itemId) { it.copy(local = chosenLocal, chosen = candidate, category = category,
            expectedLink = link, allowCreate = allowCreate, state = BulkTrackerItemState.CONFIRMED, error = null) }
        Unit
    }

    /** Rebinding after explicit verification retains uncertainty but revokes all unsent per-item consent. */
    suspend fun rebind(id: String, account: BulkTrackerAccount): Unit = operations.withLock {
        val draft = draft(id)
        require(draft.account.sameOwner(account)) { "A draft cannot move to another tracker account" }
        access.check(account)
        store.mutate { old -> old.copy(drafts = old.drafts.map { current ->
            if (current.id != id) current else current.copy(account = account, error = null, retryAt = 0,
                items = current.items.map { item -> when {
                    item.uncertain -> item.copy(state = BulkTrackerItemState.READBACK_REQUIRED)
                    item.state == BulkTrackerItemState.APPLIED || item.state == BulkTrackerItemState.READY_TO_LINK -> item
                    else -> item.copy(state = if (item.chosen == null) BulkTrackerItemState.UNRESOLVED else
                        BulkTrackerItemState.SUGGESTED, allowCreate = false)
                } })
        }) }
        Unit
    }

    suspend fun apply(id: String) = operations.withLock {
        val draft = draft(id)
        access.check(draft.account)
        check(draft.retryAt <= now()) { "The provider cooldown has not elapsed" }
        for (item in draft.items) {
            currentCoroutineContext().ensureActive()
            access.check(draft.account)
            if (item.state !in setOf(BulkTrackerItemState.CONFIRMED, BulkTrackerItemState.READY_TO_LINK,
                    BulkTrackerItemState.READBACK_REQUIRED)) continue
            try {
                if (item.state == BulkTrackerItemState.READBACK_REQUIRED) readback(draft, item)
                else applyItem(draft, item)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val current = draft(id).items.single { it.id == item.id }
                update(id, item.id) { it.copy(state = if (current.uncertain) BulkTrackerItemState.READBACK_REQUIRED else
                    if (current.state == BulkTrackerItemState.READY_TO_LINK) current.state else BulkTrackerItemState.FAILED,
                    error = diagnostic(e)) }
                if (stopsPass(e)) { failDraft(id, e); break }
            }
        }
    }

    suspend fun check(id: String, itemId: String) = operations.withLock {
        val draft = draft(id)
        val item = draft.items.single { it.id == itemId }
        require(item.uncertain || item.state == BulkTrackerItemState.READY_TO_LINK)
        check(draft.retryAt <= now()) { "The provider cooldown has not elapsed" }
        try { readback(draft, item) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (stopsPass(e)) failDraft(id, e)
            throw e
        }
    }

    suspend fun discard(id: String): Unit = operations.withLock {
        val current = draft(id)
        check(current.items.none { it.uncertain }) { "Check uncertain additions before discarding this draft" }
        store.mutate { it.copy(drafts = it.drafts.filterNot { draft -> draft.id == id }) }
        Unit
    }

    suspend fun canCreate(account: BulkTrackerAccount) = access.createStatus(account) != null

    private suspend fun applyItem(draft: BulkTrackerDraft, item: BulkTrackerItem) {
        validate(draft, item)
        val candidate = requireNotNull(item.chosen)
        val existing = access.entry(draft.account, candidate.mediaId) { validate(draft, item) }
        if (existing != null) {
            val ready = update(draft.id, item.id) { it.copy(state = BulkTrackerItemState.READY_TO_LINK,
                remote = BulkTrackerRemote.from(existing), error = null) }
            commit(draft, ready, existing)
            return
        }
        if (item.state == BulkTrackerItemState.READY_TO_LINK || !item.allowCreate) {
            update(draft.id, item.id) { it.copy(state = BulkTrackerItemState.FAILED, allowCreate = false,
                error = "This title is missing from the remote list. Adding it requires a new explicit review.") }
            return
        }
        val status = requireNotNull(access.createStatus(draft.account)) { "This provider lane is read-only" }
        checkNoOtherUncertainAddition(draft, item)
        val creating = update(draft.id, item.id) { it.copy(state = BulkTrackerItemState.CREATING, error = null) }
        try {
            access.create(draft.account, candidate.mediaId, status) { validate(draft, creating) }
            val uncertain = update(draft.id, item.id) { it.copy(state = BulkTrackerItemState.READBACK_REQUIRED) }
            readback(draft, uncertain)
        } catch (e: CancellationException) {
            withContext(NonCancellable + Dispatchers.IO) { update(draft.id, item.id) {
                it.copy(state = BulkTrackerItemState.READBACK_REQUIRED, error = "Interrupted addition; check the remote entry")
            } }
            throw e
        } catch (e: Exception) {
            // Even a failed provider readback can follow a successful creation. Never infer it was not sent.
            update(draft.id, item.id) { it.copy(state = BulkTrackerItemState.READBACK_REQUIRED, error = diagnostic(e)) }
            throw e
        }
    }

    private suspend fun readback(draft: BulkTrackerDraft, item: BulkTrackerItem) {
        validate(draft, item)
        val entry = access.entry(draft.account, requireNotNull(item.chosen).mediaId) { validate(draft, item) }
        if (entry == null) {
            update(draft.id, item.id) { it.copy(state = BulkTrackerItemState.FAILED, allowCreate = false,
                error = "No remote entry was found. Review a new addition explicitly before sending again.") }
        } else {
            val ready = update(draft.id, item.id) { it.copy(state = BulkTrackerItemState.READY_TO_LINK,
                remote = BulkTrackerRemote.from(entry), error = null) }
            commit(draft, ready, entry)
        }
    }

    private suspend fun commit(draft: BulkTrackerDraft, item: BulkTrackerItem, entry: TrackerRemoteEntry) {
        validate(draft, item)
        require(entry.reference.owner == draft.account.connection().owner && entry.reference.mediaId == item.chosen?.mediaId)
        access.commit(draft.account, item, entry)
        update(draft.id, item.id) { it.copy(state = BulkTrackerItemState.APPLIED, allowCreate = false, error = null) }
    }

    private suspend fun validate(expected: BulkTrackerDraft, item: BulkTrackerItem) {
        currentCoroutineContext().ensureActive()
        val actual = draft(expected.id)
        check(actual.account == expected.account && actual.items.single { it.id == item.id } == item) { "This draft choice changed" }
        access.check(expected.account)
        val local = requireNotNull(item.local)
        val current = access.local(local.id)
        check(current?.sameEdition(local) ?: local.fromSearch) { "The local source edition is missing or changed" }
        check(access.link(expected.account, local.id) == item.expectedLink) { "The local tracker link changed; review it again" }
        if (item.state == BulkTrackerItemState.CREATING) checkNoOtherUncertainAddition(expected, item)
    }

    /** An interrupted request blocks another draft for the same raw owner/work as well as this item. */
    private fun checkNoOtherUncertainAddition(draft: BulkTrackerDraft, item: BulkTrackerItem) {
        val mediaId = requireNotNull(item.chosen).mediaId
        check(store.current().drafts.none { other -> other.account.sameOwner(draft.account) && other.items.any {
            (other.id != draft.id || it.id != item.id) && it.uncertain && it.chosen?.mediaId == mediaId
        } }) { "Check the uncertain addition for this account and work in the other saved draft first" }
    }

    private fun update(id: String, itemId: String, transform: (BulkTrackerItem) -> BulkTrackerItem): BulkTrackerItem {
        var result: BulkTrackerItem? = null
        store.mutate { old -> old.copy(drafts = old.drafts.map { draft ->
            if (draft.id != id) draft else draft.copy(items = draft.items.map { item ->
                if (item.id != itemId) item else transform(item).also { result = it }
            })
        }) }
        return requireNotNull(result)
    }

    private fun failDraft(id: String, error: Exception) {
        val cooldown = when (error) {
            is TrackerProviderError -> error.cooldownMillis.takeIf { it > 0 } ?: if (error.status == 429) 60_000L else 0L
            is TrackerHttpException -> error.retryAfterMillis.takeIf { it > 0 } ?: if (error.code == 429) 60_000L else 0L
            else -> 0L
        }.coerceIn(0, 7L * 24 * 60 * 60 * 1000)
        store.mutate { old -> old.copy(drafts = old.drafts.map {
            if (it.id == id) it.copy(error = diagnostic(error), retryAt = if (cooldown > 0) now() + cooldown else 0) else it
        }) }
    }

    private fun stopsPass(error: Exception): Boolean = when (error) {
        is TrackerProviderError -> error.status in setOf(401, 403, 429)
        is TrackerHttpException -> error.code in setOf(401, 403, 429)
        else -> false
    }

    private fun diagnostic(error: Exception): String = when (error) {
        is TrackerProviderError -> "${error.provider}: HTTP ${error.status}; verify access or wait for the provider cooldown"
        is TrackerHttpException -> "Tracker HTTP ${error.code}; verify access or wait for the provider cooldown"
        else -> "${error.javaClass.simpleName}: this item could not finish; its choices were preserved for review"
    }.take(2048)

    private fun uuid() = UUID.randomUUID().toString()
}
