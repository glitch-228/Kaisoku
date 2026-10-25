package org.koitharu.kotatsu.backups.webdav

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID
import javax.inject.Inject

data class WebDavSettingsState(
    val configured: Boolean = false,
    val displayName: String = "",
    val endpoint: String = "",
    val revision: String? = null,
    val scheduleHours: Int = 0,
    val wifiOnly: Boolean = true,
    val keepBackups: Int = 0,
    val busy: Boolean = false,
    val resources: List<WebDavResource> = emptyList(),
    val listingRevision: String? = null,
    val status: WebDavUiStatus = WebDavUiStatus.IDLE,
)

enum class WebDavUiStatus { IDLE, WORKING, LISTED, UPLOADED, CANCELLED }

sealed interface WebDavUiEvent {
    data class Error(val error: Throwable) : WebDavUiEvent
    data class Restore(val uri: Uri, val revision: String) : WebDavUiEvent
    data class Uploaded(val backup: WebDavResource, val warning: String?) : WebDavUiEvent
}

@HiltViewModel
class WebDavSettingsViewModel @Inject constructor(
    private val configuration: WebDavConfigurationStore,
    private val repository: WebDavBackupRepository,
    private val scheduler: WebDavScheduler,
) : ViewModel() {
    private val mutableState = MutableStateFlow(WebDavSettingsState())
    val state = mutableState.asStateFlow()
    private val eventChannel = Channel<WebDavUiEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()
    private var operation: Job? = null

    init {
        action { load() }
    }

    suspend fun editableConnection(): WebDavConnection? = configuration.connection()

    fun configure(
        expectedRevision: String?, displayName: String, endpoint: String, username: String,
        newPassword: String, clearPassword: Boolean, allowCleartext: Boolean,
    ) = action {
        val previous = configuration.connection()
        check(previous?.revision == expectedRevision) { "The connection changed; open its settings again" }
        val location = WebDavEndpoint.parse(endpoint, allowCleartext)
        val sameAccount = previous?.endpoint.toString() == location.toString() && previous?.username == username
        val password = if (clearPassword) "" else if (newPassword.isNotEmpty()) newPassword
            else if (sameAccount) previous?.password.orEmpty() else ""
        val connection = WebDavConnection(
            if (sameAccount) checkNotNull(previous).id else UUID.randomUUID().toString(),
            UUID.randomUUID().toString(), displayName.trim(), location, username, password, allowCleartext,
            previous?.scheduleHours ?: 0, previous?.wifiOnly ?: true, previous?.keepBackups ?: 0,
        )
        configuration.save(connection)
        scheduler.schedule()
        load(clearResults = true)
    }

    fun schedule(hours: Int) = edit { copy(scheduleHours = hours) }
    fun wifiOnly(value: Boolean) = edit { copy(wifiOnly = value) }
    fun keepBackups(count: Int) = edit { copy(keepBackups = count) }

    fun disconnect() = action {
        configuration.disconnect()
        scheduler.schedule()
        load(clearResults = true)
    }

    fun list() = action {
        val connection = configuration.connection() ?: throw IOException("Configure WebDAV first")
        val resources = repository.list()
        configuration.requireCurrent(connection)
        mutableState.value = mutableState.value.copy(resources = resources, listingRevision = connection.revision,
            status = WebDavUiStatus.LISTED)
    }

    fun upload() = action {
        val result = repository.upload()
        eventChannel.send(WebDavUiEvent.Uploaded(result.backup, result.retainedWarning))
        mutableState.value = mutableState.value.copy(status = WebDavUiStatus.UPLOADED)
        // Upload already verified its final object; do not replace a successful outcome with a new list failure.
        mutableState.value = mutableState.value.copy(resources =
            (listOf(result.backup) + mutableState.value.resources).distinctBy { it.name },
            listingRevision = mutableState.value.revision)
    }

    fun restore(resource: WebDavResource) = action {
        val revision = mutableState.value.listingRevision
        if (revision == null || revision != configuration.connection()?.revision) {
            throw IOException("The connection changed; refresh its backup list")
        }
        val uri = repository.download(resource)
        eventChannel.send(WebDavUiEvent.Restore(uri, revision))
    }

    fun cancel() {
        operation?.cancel()
        mutableState.value = mutableState.value.copy(status = WebDavUiStatus.CANCELLED)
    }

    private fun edit(change: WebDavConnection.() -> WebDavConnection) = action {
        val connection = configuration.connection() ?: throw IOException("Configure WebDAV first")
        configuration.save(connection.change())
        scheduler.schedule()
        load(clearResults = true)
    }

    private fun WebDavConnection.copy(
        scheduleHours: Int = this.scheduleHours,
        wifiOnly: Boolean = this.wifiOnly,
        keepBackups: Int = this.keepBackups,
    ) = WebDavConnection(id, UUID.randomUUID().toString(), displayName, endpoint, username, password,
        allowCleartextLan, scheduleHours, wifiOnly, keepBackups)

    private suspend fun load(clearResults: Boolean = false) {
        val connection = configuration.connection()
        mutableState.value = mutableState.value.copy(
            configured = connection != null, displayName = connection?.displayName.orEmpty(),
            endpoint = connection?.endpoint?.toString().orEmpty(), revision = connection?.revision,
            scheduleHours = connection?.scheduleHours ?: 0, wifiOnly = connection?.wifiOnly ?: true,
            keepBackups = connection?.keepBackups ?: 0,
            resources = if (clearResults) emptyList() else mutableState.value.resources,
            listingRevision = if (clearResults) null else mutableState.value.listingRevision,
        )
    }

    private fun action(block: suspend () -> Unit) {
        if (operation?.isActive == true) return
        operation = viewModelScope.launch {
            mutableState.value = mutableState.value.copy(busy = true)
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                eventChannel.send(WebDavUiEvent.Error(e))
            } finally {
                mutableState.value = mutableState.value.copy(busy = false)
            }
        }
    }
}
