package org.koitharu.kotatsu.scrobbling.v2

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.dialog.buildAlertDialog
import org.koitharu.kotatsu.core.util.ext.copyToClipboard
import org.koitharu.kotatsu.core.util.ext.getCopyableErrorDetails
import javax.inject.Inject

/** Callback-only activity. The one-use authorization attempt remains in the process, never an Intent extra. */
@AndroidEntryPoint
class ComickAuthActivity : AppCompatActivity() {
    @Inject lateinit var tools: TrackerToolsRepository
    private var handling = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { setText(R.string.loading_) })
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent) {
        if (handling) return
        handling = true
        val callback = intent.dataString
        lifecycleScope.launch {
            try {
                requireNotNull(callback) { getString(R.string.tracker_tools_auth_restart) }
                tools.finishComick(callback)
                android.widget.Toast.makeText(this@ComickAuthActivity, R.string.tracker_tools_auth_done,
                    android.widget.Toast.LENGTH_SHORT).show()
                finish()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val details = e.getCopyableErrorDetails()
                buildAlertDialog(this@ComickAuthActivity) {
                    setTitle(R.string.error)
                    setMessage(R.string.tracker_tools_auth_restart)
                    setNeutralButton(R.string.synopsis_translation_copy_error) { _, _ ->
                        copyToClipboard(getString(R.string.error), details)
                        finish()
                    }
                    setPositiveButton(android.R.string.ok) { _, _ -> finish() }
                    setOnCancelListener { finish() }
                }.show()
            }
        }
    }
}
