package org.koitharu.kotatsu.details.ui

import org.koitharu.kotatsu.core.archive.retireArchiveDisplay
import org.koitharu.kotatsu.core.archive.ArchiveDisplaySession

import android.app.Activity
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.view.MenuProvider
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.community.data.isCommunitySimilarSeed
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.os.AppShortcutManager
import org.koitharu.kotatsu.core.ui.dialog.buildAlertDialog
import org.koitharu.kotatsu.core.util.ext.isHttpUrl
import org.koitharu.kotatsu.details.translation.SynopsisTranslationSheet
import org.koitharu.kotatsu.core.archive.ArchiveAccess
import org.koitharu.kotatsu.core.archive.ArchiveUnlockDialog
import org.koitharu.kotatsu.core.archive.EncryptedArchiveExportDialog
import java.io.File

class DetailsMenuProvider(
    private val activity: FragmentActivity,
    private val viewModel: DetailsViewModel,
    private val snackbarHost: View,
    private val appShortcutManager: AppShortcutManager,
) : MenuProvider, ActivityResultCallback<ActivityResult> {

    private var archiveFile: File? = null
    private var protectedArchive = false

    init {
        activity.supportFragmentManager.setFragmentResultListener(ArchiveUnlockDialog.RESULT_UNLOCKED, activity) { _, result ->
            if (result.getString(ArchiveUnlockDialog.KEY_FILE) == archiveFile?.canonicalPath) {
                activity.invalidateOptionsMenu()
                viewModel.reload(force = false)
            }
        }
        val lockListener = ArchiveDisplaySession(
            sessions = ArchiveAccess.get(activity).sessions,
            currentArchives = { listOfNotNull(viewModel.localArchiveFile) },
            retire = {
                viewModel.retireArchiveSession()
                activity.runOnUiThread { retireArchiveDisplay(activity, activity.findViewById(android.R.id.content)) {} }
            },
        )
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) { lockListener.close() }
        })
        activity.lifecycleScope.launch {
            viewModel.mangaDetails.collect {
                val file = viewModel.localArchiveFile
                archiveFile = file
                protectedArchive = file != null // Fail closed while asynchronous header inspection is pending.
                // Public directory inspection belongs on IO, never in menu callbacks.
                protectedArchive = file != null && try {
                    withContext(Dispatchers.IO) { ArchiveAccess.get(activity).isSensitive(file) }
                } catch (error: CancellationException) { throw error } catch (_: Exception) { true }
                activity.invalidateOptionsMenu()
            }
        }
    }

    private val activityForResultLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
        this,
    )

    private val router: AppRouter
        get() = activity.router

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.opt_details, menu)
    }

    override fun onPrepareMenu(menu: Menu) {
        val manga = viewModel.manga.value
        menu.findItem(R.id.action_community_similar).isVisible =
            (activity as? DetailsActivity)?.settings?.let { it.isCommunitySimilarEnabled && !it.useLegacySuggestions } == true &&
                manga != null && manga.isCommunitySimilarSeed && !protectedArchive
        menu.findItem(R.id.action_refresh_cover).isVisible =
            (activity as? DetailsActivity)?.settings?.isCoverRefreshActionEnabled == true &&
                viewModel.coverUrl.value?.isHttpUrl() == true && !protectedArchive
        menu.findItem(R.id.action_share).isVisible = manga != null && AppRouter.isShareSupported(manga)
        menu.findItem(R.id.action_save).isVisible = manga?.source != null && manga.source != LocalMangaSource
        menu.findItem(R.id.action_delete).isVisible = manga?.source == LocalMangaSource
        menu.findItem(R.id.action_browser).isVisible = manga?.publicUrl?.isHttpUrl() == true
        menu.findItem(R.id.action_alternatives).isVisible = manga?.source != LocalMangaSource
        menu.findItem(R.id.action_replace_source).isVisible = manga?.source != LocalMangaSource && manga?.id != 0L
        menu.findItem(R.id.action_shortcut).isVisible = ShortcutManagerCompat.isRequestPinShortcutSupported(activity)
        menu.findItem(R.id.action_scrobbling).isVisible = viewModel.isScrobblingAvailable
        menu.findItem(R.id.action_online).isVisible = viewModel.remoteManga.value != null
        menu.findItem(R.id.action_stats).isVisible = viewModel.isStatsAvailable.value
        menu.findItem(R.id.action_saved_metadata_only).apply {
            isVisible = manga != null && manga.source != LocalMangaSource && manga.id != 0L
            isChecked = viewModel.isSavedMetadataOnly.value
        }
        menu.findItem(R.id.action_translate_synopsis).isVisible =
            !viewModel.mangaDetails.value?.description.isNullOrBlank()
        menu.findItem(R.id.action_archive_unlock).apply {
            isVisible = protectedArchive && archiveFile != null
            title = activity.getString(if (archiveFile?.let { ArchiveAccess.sessions.isUnlocked(it) } == true)
                R.string.archive_lock else R.string.archive_unlock_beta)
        }
        menu.findItem(R.id.action_archive_export).isVisible = archiveFile != null
        val isFavourite = viewModel.favouriteCategories.value.isNotEmpty()
        menu.findItem(R.id.action_favourite).setIcon(
            if (isFavourite) R.drawable.ic_heart else R.drawable.ic_heart_outline,
        )
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        val manga = viewModel.getMangaOrNull() ?: return false
        when (menuItem.itemId) {
            R.id.action_share -> {
                router.showShareDialog(manga)
            }

            R.id.action_favourite -> {
                router.showFavoriteDialog(manga)
            }

            R.id.action_delete -> {
                buildAlertDialog(activity) {
                    setTitle(R.string.delete_manga)
                    setMessage(activity.getString(R.string.text_delete_local_manga, manga.title))
                    setPositiveButton(R.string.delete) { _, _ -> viewModel.deleteLocal() }
                    setNegativeButton(android.R.string.cancel, null)
                }.show()
            }

            R.id.action_save -> {
                router.showDownloadDialog(manga, snackbarHost)
            }

            R.id.action_browser -> {
                router.openBrowser(url = manga.publicUrl, source = manga.source, title = manga.title)
            }

            R.id.action_online -> {
                router.openDetails(viewModel.remoteManga.value ?: return false)
            }

            R.id.action_related -> {
                router.openSearch(manga.title)
            }

            R.id.action_alternatives -> {
                router.openAlternatives(manga)
            }

            R.id.action_replace_source -> {
                router.openSourceReplacement(listOf(manga.id))
            }

            R.id.action_stats -> {
                router.showStatisticSheet(manga)
            }

            R.id.action_scrobbling -> {
                router.showScrobblingSelectorSheet(manga, null)
            }

            R.id.action_shortcut -> {
                activity.lifecycleScope.launch {
                    if (!appShortcutManager.requestPinShortcut(manga)) {
                        Snackbar.make(snackbarHost, R.string.operation_not_supported, Snackbar.LENGTH_SHORT)
                            .show()
                    }
                }
            }

            R.id.action_edit_override -> {
                val intent = AppRouter.overrideEditIntent(activity, manga)
                activityForResultLauncher.launch(intent)
            }

            R.id.action_translate_synopsis -> {
                val text = viewModel.mangaDetails.value?.description?.toString()?.takeIf { it.isNotBlank() }
                    ?: return false
                SynopsisTranslationSheet.show(activity.supportFragmentManager, text, allowPersistentCache = !protectedArchive)
            }

            R.id.action_archive_unlock -> {
                val file = archiveFile ?: return false
                if (ArchiveAccess.sessions.isUnlocked(file)) ArchiveAccess.sessions.lock(file)
                else ArchiveUnlockDialog.show(activity.supportFragmentManager, file)
                activity.invalidateOptionsMenu()
            }

            R.id.action_archive_export -> {
                val file = archiveFile ?: return false
                if (protectedArchive && !ArchiveAccess.sessions.isUnlocked(file)) ArchiveUnlockDialog.show(activity.supportFragmentManager, file)
                else EncryptedArchiveExportDialog.show(activity.supportFragmentManager, file)
            }

            R.id.action_saved_metadata_only -> {
                if (viewModel.isSavedMetadataOnly.value) {
                    viewModel.setSavedMetadataOnly(false)
                } else {
                    buildAlertDialog(activity) {
                        setTitle(R.string.saved_metadata_only)
                        setMessage(R.string.saved_metadata_only_summary)
                        setPositiveButton(android.R.string.ok) { _, _ -> viewModel.setSavedMetadataOnly(true) }
                        setNegativeButton(android.R.string.cancel, null)
                    }.show()
                }
            }

            R.id.action_refresh_cover -> (activity as? DetailsActivity)?.refreshCover()
            R.id.action_community_similar -> (activity as? DetailsActivity)?.let { details ->
                viewModel.getMangaOrNull()?.let { seed ->
                    details.communitySimilarUi.show(seed, snackbarHost, activity.lifecycleScope, router)
                }
            }

            else -> return false
        }
        return true
    }

    override fun onActivityResult(result: ActivityResult) {
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.reload(force = false)
        }
    }
}
