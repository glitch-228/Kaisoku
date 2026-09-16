package org.koitharu.kotatsu.reader.ui.upscale

import android.app.Dialog
import android.os.Bundle
import android.os.PowerManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.reader.domain.UpscaleEffect
import javax.inject.Inject

@AndroidEntryPoint
class UpscaleSettingsDialog : AppCompatDialogFragment() {
	@Inject lateinit var settings: AppSettings

	override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
		val context = requireContext()
		val content = LinearLayout(context).apply {
			orientation = LinearLayout.VERTICAL
			val padding = (24 * resources.displayMetrics.density).toInt()
			setPadding(padding, 0, padding, padding)
		}
		val supported = UpscaleEffect.isSupported
		val toggle = SwitchMaterial(context).apply {
			setText(R.string.enabled)
			isChecked = settings.isReaderUpscaleEnabled
			isEnabled = supported
			setOnCheckedChangeListener { _, checked -> settings.isReaderUpscaleEnabled = checked }
		}
		content.addView(toggle)
		content.addView(TextView(context).apply {
			setText(when {
				!supported -> R.string.reader_upscale_unsupported
				context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true -> R.string.upscale_power_save
				else -> R.string.upscale_activation_help
			})
		})
		val strength = TextView(context)
		content.addView(strength)
		val slider = Slider(context).apply {
			valueFrom = 0f
			valueTo = 100f
			stepSize = 1f
			value = settings.readerUpscaleStrength.toFloat()
			contentDescription = getString(R.string.upscale_strength)
			isEnabled = supported
			addOnChangeListener { _, value, fromUser ->
				if (fromUser) settings.readerUpscaleStrength = value.toInt()
				strength.text = getString(R.string.upscale_strength_value, value.toInt())
			}
		}
		content.addView(slider)
		val passes = MaterialButton(context)
		val threshold = MaterialButton(context)
		fun bind() {
			slider.value = settings.readerUpscaleStrength.toFloat()
			strength.text = getString(R.string.upscale_strength_value, settings.readerUpscaleStrength)
			passes.text = getString(R.string.upscale_passes_value,
				if (settings.readerUpscalePasses == 0) getString(R.string.automatic) else settings.readerUpscalePasses.toString())
			threshold.text = getString(R.string.upscale_threshold_value, settings.readerUpscaleThreshold.toString())
		}
		passes.isEnabled = supported
		passes.setOnClickListener {
			MaterialAlertDialogBuilder(context).setTitle(R.string.upscale_passes)
				.setSingleChoiceItems(arrayOf(getString(R.string.automatic), "1", "2", "3", "4"), settings.readerUpscalePasses) { dialog, which ->
					settings.readerUpscalePasses = which
					bind()
					dialog.dismiss()
				}.show()
		}
		threshold.isEnabled = supported
		threshold.setOnClickListener {
			val values = listOf(1f, 1.5f, 2f, 3f)
			MaterialAlertDialogBuilder(context).setTitle(R.string.upscale_threshold)
				.setSingleChoiceItems(values.map { "${it}×" }.toTypedArray(), values.indexOf(settings.readerUpscaleThreshold)) { dialog, which ->
					settings.readerUpscaleThreshold = values[which]
					bind()
					dialog.dismiss()
				}.show()
		}
		content.addView(passes)
		content.addView(threshold)
		bind()
		return MaterialAlertDialogBuilder(context).setTitle(R.string.reader_upscale).setView(content)
			.setPositiveButton(R.string.close, null)
			.setNeutralButton(R.string.reset, null).create().apply {
				setOnShowListener {
					getButton(android.content.DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
						settings.readerUpscaleStrength = 75
						settings.readerUpscalePasses = 0
						settings.readerUpscaleThreshold = 1.5f
						bind()
					}
				}
			}
	}
}
