package org.koitharu.kotatsu.reader.ui

import android.app.Dialog
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.setPadding
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.reader.data.SourceSwitchPromptStore
import org.koitharu.kotatsu.reader.domain.SourceSwitchPromptPolicy
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** The same lifecycle-owned controls serve the reader and Reader settings. */
@AndroidEntryPoint
class SourceSwitchMuteDialog : DialogFragment() {
    @Inject lateinit var store: SourceSwitchPromptStore

    private var saveForm: (() -> Bundle)? = null
    private var retainedForm: Bundle? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val state = savedInstanceState?.getBundle("form") ?: retainedForm
        val currentSource = arguments?.getString("source")
        val initialSource = if (state?.containsKey("target") == true) state.getString("target") else currentSource
        val targets = (listOf(null, currentSource) + store.activeRules().map { it.sourceName }).distinct()
        val padding = (24 * resources.displayMetrics.density).toInt()
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding)
        }
        fun label(text: Int) {
            body.addView(TextView(context).apply { setText(text) })
        }
        fun spinner(items: List<String>, selection: Int): Spinner = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, items)
            setSelection(selection.coerceIn(0, items.lastIndex))
            body.addView(this)
        }
        label(R.string.source_prompt_target)
        val target = spinner(targets.map { name ->
            name?.let { MangaSource(it).getTitle(context) } ?: getString(R.string.source_prompt_all_sources)
        }, targets.indexOf(initialSource).coerceAtLeast(0))
        label(R.string.source_prompt_scope)
        val scope = spinner(listOf(getString(R.string.source_prompt_slow_only), getString(R.string.source_prompt_all)),
            state?.getInt("scope", 0) ?: 0)
        label(R.string.source_prompt_duration)
        val duration = spinner(resources.getStringArray(R.array.source_prompt_durations).toList(),
            state?.getInt("duration", 1) ?: 1)
        val customRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val amount = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.source_prompt_custom_amount)
            setText(state?.getString("amount").orEmpty())
            customRow.addView(this, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        val unit = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item,
                listOf(getString(R.string.source_prompt_hours), getString(R.string.source_prompt_days)))
            setSelection(state?.getInt("unit", 0) ?: 0)
            customRow.addView(this)
        }
        body.addView(customRow)
        duration.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                customRow.visibility = if (position == 6) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        customRow.visibility = if (duration.selectedItemPosition == 6) View.VISIBLE else View.GONE
        body.addView(TextView(context).apply { setText(R.string.source_prompt_scope_help) })
        label(R.string.source_prompt_active)
        val active = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        body.addView(active)
        fun refreshRules() {
            active.removeAllViews()
            val rules = store.activeRules()
            if (rules.isEmpty()) active.addView(TextView(context).apply { setText(R.string.source_prompt_none) })
            for (rule in rules) {
                val title = rule.sourceName?.let { MangaSource(it).getTitle(context) }
                    ?: getString(R.string.source_prompt_all_sources)
                val kind = getString(if (rule.scope == SourceSwitchPromptPolicy.Scope.ALL)
                    R.string.source_prompt_all else R.string.source_prompt_slow_only)
                val expiry = rule.expiresAt?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) }
                    ?: getString(R.string.source_prompt_indefinitely)
                active.addView(TextView(context).apply { text = getString(R.string.source_prompt_rule, title, kind, expiry) })
                active.addView(MaterialButton(context).apply {
                    setText(R.string.source_prompt_resume)
                    setOnClickListener { store.resume(rule.sourceName); refreshRules() }
                })
            }
        }
        refreshRules()
        saveForm = {
            Bundle().apply {
                putString("target", targets[target.selectedItemPosition])
                putInt("scope", scope.selectedItemPosition)
                putInt("duration", duration.selectedItemPosition)
                putString("amount", amount.text.toString())
                putInt("unit", unit.selectedItemPosition)
            }
        }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.source_prompt_mute)
            .setView(ScrollView(context).apply { addView(body) })
            .setPositiveButton(R.string.source_prompt_apply, null)
            .setNegativeButton(R.string.close, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val millis = when (duration.selectedItemPosition) {
                    0 -> TimeUnit.HOURS.toMillis(1)
                    1 -> TimeUnit.HOURS.toMillis(6)
                    2 -> TimeUnit.HOURS.toMillis(12)
                    3 -> TimeUnit.DAYS.toMillis(1)
                    4 -> TimeUnit.DAYS.toMillis(3)
                    5 -> TimeUnit.DAYS.toMillis(7)
                    6 -> SourceSwitchPromptPolicy.customDuration(amount.text.toString().toLongOrNull() ?: 0L,
                        days = unit.selectedItemPosition == 1)
                    else -> null
                }
                if (duration.selectedItemPosition == 6 && millis == null) {
                    amount.error = getString(R.string.source_prompt_custom_error)
                    return@setOnClickListener
                }
                val result = runCatching {
                    store.mute(targets[target.selectedItemPosition], SourceSwitchPromptPolicy.Scope.entries[scope.selectedItemPosition], millis)
                }
                if (result.isSuccess) {
                    refreshRules()
                    Toast.makeText(context, R.string.source_prompt_saved, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, R.string.source_prompt_save_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
        return dialog
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBundle("form", saveForm?.invoke() ?: retainedForm)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        retainedForm = saveForm?.invoke()
        saveForm = null
        super.onDestroyView()
    }

    companion object {
        private const val TAG = "sourcePromptMute"

        fun show(manager: FragmentManager, sourceName: String? = null) {
            if (manager.isStateSaved || manager.findFragmentByTag(TAG) != null) return
            SourceSwitchMuteDialog().apply {
                arguments = Bundle().apply { putString("source", sourceName) }
            }.show(manager, TAG)
        }
    }
}
