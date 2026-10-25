package org.koitharu.kotatsu.backups.webdav

import android.os.Bundle
import android.text.InputType
import android.text.format.DateFormat
import android.text.format.Formatter
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.ui.BasePreferenceFragment
import java.util.Date

/** Explicit connection, list, upload and native restore actions; opening settings makes no network request. */
@AndroidEntryPoint
class WebDavSettingsFragment : BasePreferenceFragment(R.string.webdav_title) {
    private val model: WebDavSettingsViewModel by viewModels()
    private lateinit var configure: Preference
    private lateinit var list: Preference
    private lateinit var upload: Preference
    private lateinit var cancel: Preference
    private lateinit var disconnect: Preference
    private lateinit var status: Preference
    private lateinit var schedule: ListPreference
    private lateinit var wifi: SwitchPreferenceCompat
    private lateinit var retention: ListPreference
    private lateinit var files: PreferenceCategory
    private var displayedFiles: List<WebDavResource>? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = preferenceManager.context
        preferenceScreen = preferenceManager.createPreferenceScreen(context)
        preferenceScreen.addPreference(Preference(context).apply {
            setTitle(R.string.webdav_title)
            setSummary(R.string.webdav_information)
            isSelectable = false
            isPersistent = false
        })
        configure = action("webdav_configure", R.string.webdav_configure) { showEditor() }
        list = action("webdav_list", R.string.webdav_list) { model.list() }
        upload = action("webdav_upload", R.string.webdav_upload) {
            MaterialAlertDialogBuilder(context).setTitle(R.string.webdav_upload)
                .setMessage(R.string.webdav_upload_confirmation)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.webdav_upload) { _, _ -> model.upload() }.show()
        }
        cancel = action("webdav_cancel", android.R.string.cancel) { model.cancel() }
        status = Preference(context).apply {
            key = "webdav_status"
            isPersistent = false
            isSelectable = false
        }.also(preferenceScreen::addPreference)
        schedule = ListPreference(context).apply {
            key = "webdav_schedule"
            setTitle(R.string.webdav_schedule)
            entries = arrayOf(getString(R.string.webdav_off), getString(R.string.webdav_daily), getString(R.string.webdav_weekly))
            entryValues = arrayOf("0", "24", "168")
            isPersistent = false
            setOnPreferenceChangeListener { _, value ->
                (value as? String)?.toIntOrNull()?.let(model::schedule)
                false
            }
        }.also(preferenceScreen::addPreference)
        wifi = SwitchPreferenceCompat(context).apply {
            key = "webdav_wifi"
            setTitle(R.string.webdav_wifi_only)
            setSummary(R.string.webdav_wifi_summary)
            isPersistent = false
            setOnPreferenceChangeListener { _, value -> model.wifiOnly(value == true); false }
        }.also(preferenceScreen::addPreference)
        retention = ListPreference(context).apply {
            key = "webdav_retention"
            setTitle(R.string.webdav_retention)
            entries = arrayOf(getString(R.string.webdav_off), getString(R.string.webdav_keep, 2),
                getString(R.string.webdav_keep, 7), getString(R.string.webdav_keep, 30))
            entryValues = arrayOf("0", "2", "7", "30")
            isPersistent = false
            setOnPreferenceChangeListener { _, value ->
                (value as? String)?.toIntOrNull()?.let { count ->
                    if (count == 0) model.keepBackups(0) else MaterialAlertDialogBuilder(context)
                        .setTitle(R.string.webdav_retention).setMessage(R.string.webdav_retention_confirmation)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.webdav_enable) { _, _ -> model.keepBackups(count) }.show()
                }
                false
            }
        }.also(preferenceScreen::addPreference)
        disconnect = action("webdav_disconnect", R.string.webdav_disconnect) {
            MaterialAlertDialogBuilder(context).setTitle(R.string.webdav_disconnect)
                .setMessage(R.string.webdav_disconnect_confirmation)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.webdav_disconnect) { _, _ -> model.disconnect() }.show()
        }
        files = PreferenceCategory(context).apply {
            key = "webdav_files"
            setTitle(R.string.webdav_available_backups)
            isPersistent = false
        }.also(preferenceScreen::addPreference)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { model.state.collect(::render) }
                launch {
                    model.events.collect { event ->
                        when (event) {
                            is WebDavUiEvent.Error -> router.showErrorDialog(event.error)
                            is WebDavUiEvent.Restore -> if (event.revision == model.state.value.revision) {
                                router.showBackupRestoreDialog(event.uri)
                            }
                            is WebDavUiEvent.Uploaded -> MaterialAlertDialogBuilder(requireContext())
                                .setTitle(R.string.webdav_uploaded)
                                .setMessage(listOfNotNull(event.backup.name, event.warning).joinToString("\n\n"))
                                .setPositiveButton(android.R.string.ok, null).show()
                        }
                    }
                }
            }
        }
    }

    private fun render(state: WebDavSettingsState) {
        configure.isEnabled = !state.busy
        configure.summary = if (state.configured) "${state.displayName}\n${state.endpoint}" else getString(R.string.webdav_not_configured)
        list.isEnabled = state.configured && !state.busy
        upload.isEnabled = list.isEnabled
        disconnect.isEnabled = list.isEnabled
        schedule.isEnabled = list.isEnabled
        wifi.isEnabled = list.isEnabled
        retention.isEnabled = list.isEnabled
        cancel.isVisible = state.busy
        status.title = getString(when {
            state.busy -> R.string.webdav_working
            state.status == WebDavUiStatus.CANCELLED -> R.string.webdav_cancelled
            state.status == WebDavUiStatus.UPLOADED -> R.string.webdav_uploaded
            state.status == WebDavUiStatus.LISTED -> R.string.webdav_list_ready
            else -> R.string.webdav_idle
        })
        schedule.value = state.scheduleHours.toString()
        schedule.summary = schedule.entry
        wifi.isChecked = state.wifiOnly
        retention.value = state.keepBackups.toString()
        retention.summary = retention.entry
        files.isVisible = state.resources.isNotEmpty() || state.status == WebDavUiStatus.LISTED
        if (displayedFiles != state.resources) {
            displayedFiles = state.resources
            files.removeAll()
            state.resources.forEach { resource ->
                files.addPreference(Preference(requireContext()).apply {
                    key = "webdav_file_${resource.name}"
                    title = resource.name
                    summary = buildString {
                        append(Formatter.formatFileSize(context, resource.size))
                        if (resource.modifiedAt > 0) {
                            append(" · ")
                            append(DateFormat.getMediumDateFormat(context).format(Date(resource.modifiedAt)))
                        }
                    }
                    isPersistent = false
                    setOnPreferenceClickListener {
                        MaterialAlertDialogBuilder(context).setTitle(R.string.webdav_restore_choose)
                            .setMessage(getString(R.string.webdav_restore_confirmation, resource.name))
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton(R.string.webdav_download) { _, _ -> model.restore(resource) }.show()
                        true
                    }
                })
            }
            if (state.resources.isEmpty()) files.addPreference(Preference(requireContext()).apply {
                setTitle(R.string.webdav_no_backups)
                isSelectable = false
                isPersistent = false
            })
        }
        files.isEnabled = !state.busy
    }

    private fun action(keyName: String, titleId: Int, block: () -> Unit): Preference = Preference(preferenceManager.context)
        .apply {
            key = keyName
            setTitle(titleId)
            isPersistent = false
            setOnPreferenceClickListener { block(); true }
        }.also(preferenceScreen::addPreference)

    private fun showEditor() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                showEditor(model.editableConnection())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                router.showErrorDialog(e)
            }
        }
    }

    private fun showEditor(previous: WebDavConnection?) {
        val context = requireContext()
        val margin = (24 * resources.displayMetrics.density).toInt()
        val form = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(margin, margin / 2, margin, margin / 2)
            isSaveEnabled = false
            isSaveFromParentEnabled = false
        }
        fun field(hintId: Int, value: String, input: Int = InputType.TYPE_CLASS_TEXT): EditText = EditText(context).apply {
            setHint(hintId)
            inputType = input
            setSingleLine(true)
            isSaveEnabled = false
            isSaveFromParentEnabled = false
            setText(value)
            form.addView(this)
        }
        val name = field(R.string.webdav_name, previous?.displayName ?: getString(R.string.webdav_default_name))
        val endpoint = field(R.string.webdav_endpoint, previous?.endpoint?.toString().orEmpty(),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val username = field(R.string.webdav_username, previous?.username.orEmpty(),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        val password = field(if (previous == null) R.string.webdav_password else R.string.webdav_password_keep, "",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD).apply {
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        val clear = CheckBox(context).apply {
            setText(R.string.webdav_clear_password)
            isVisibleCompat(previous != null)
            isSaveEnabled = false
        }.also(form::addView)
        val cleartext = CheckBox(context).apply {
            setText(R.string.webdav_allow_lan)
            isChecked = previous?.allowCleartextLan == true
            isSaveEnabled = false
        }.also(form::addView)
        val scroll = ScrollView(context).apply { addView(form); isSaveEnabled = false }
        val dialog = MaterialAlertDialogBuilder(context).setTitle(R.string.webdav_configure)
            .setView(scroll).setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.webdav_save, null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    WebDavEndpoint.parse(endpoint.text.toString(), cleartext.isChecked)
                    model.configure(previous?.revision, name.text.toString(), endpoint.text.toString(),
                        username.text.toString(), password.text.toString(), clear.isChecked, cleartext.isChecked)
                    password.text?.clear()
                    dialog.dismiss()
                } catch (e: IllegalArgumentException) {
                    endpoint.error = e.message ?: getString(R.string.webdav_invalid_endpoint)
                }
            }
        }
        dialog.setOnDismissListener { password.text?.clear() }
        dialog.show()
    }

    private fun View.isVisibleCompat(visible: Boolean) {
        visibility = if (visible) View.VISIBLE else View.GONE
    }
}
