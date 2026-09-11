/*
 * Ported from Kototoro (Apache-2.0).
 * Copyright 2025 Kototoro contributors.
 * Copyright 2026 Kaisoku contributors.
 */
package org.koitharu.kotatsu.reader.ui.novel

import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import androidx.core.graphics.ColorUtils
import androidx.core.view.updateLayoutParams
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.slider.Slider
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.databinding.SheetNovelReaderConfigBinding

class NovelReaderConfigSheet : BottomSheetDialogFragment(),
	Slider.OnChangeListener,
	CompoundButton.OnCheckedChangeListener,
	View.OnClickListener {

	private var _binding: SheetNovelReaderConfigBinding? = null
	private val binding get() = _binding!!

	private lateinit var settings: NovelReaderSettings
	private var callback: Callback? = null

	private val handler = Handler(Looper.getMainLooper())
	private var updateRunnable: Runnable? = null
	private val updateDelay = 150L

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View {
		_binding = SheetNovelReaderConfigBinding.inflate(inflater, container, false)
		return binding.root
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		callback = activity as? Callback

		settings = NovelReaderSettings.load(requireContext()).normalized()

		configureSliders()
		syncControlsFromSettings()
		binding.switchDualPage.isChecked = settings.enableDualPage
		binding.switchFullscreen.isChecked = settings.enableFullscreen
		binding.switchShowReadingStatus.isChecked = settings.showReadingStatus
		binding.switchReadingStatusTransparent.isChecked = settings.isReadingStatusTransparent
		binding.switchParagraphIndent.isChecked = settings.enableParagraphIndent
		binding.toggleGroupReadingMode.check(
			if (settings.readingMode == NovelReadingMode.SCROLL) R.id.btnModeScroll else R.id.btnModePaged,
		)
		binding.toggleGroupPageTurnAnimation.check(
			if (settings.pageTurnAnimation == NovelPageTurnAnimation.SIMULATION) {
				R.id.btnAnimationSimulation
			} else {
				R.id.btnAnimationSlide
			},
		)
		binding.toggleGroupThemePreset.check(
			when (settings.themePreset) {
				NovelReaderThemePreset.PAPER -> R.id.btnThemePaper
				NovelReaderThemePreset.SEPIA -> R.id.btnThemeSepia
				NovelReaderThemePreset.MOSS -> R.id.btnThemeMoss
				NovelReaderThemePreset.SLATE -> R.id.btnThemeSlate
			},
		)

		updateValueDisplays()
		updatePageTurnAnimationEnabled()
		updatePreviewCard()

		binding.sliderFontSize.addOnChangeListener(this)
		binding.sliderLineSpacing.addOnChangeListener(this)
		binding.sliderParagraphSpacing.addOnChangeListener(this)
		binding.sliderMarginHorizontal.addOnChangeListener(this)
		binding.sliderMarginVertical.addOnChangeListener(this)
		binding.switchDualPage.setOnCheckedChangeListener(this)
		binding.switchFullscreen.setOnCheckedChangeListener(this)
		binding.switchShowReadingStatus.setOnCheckedChangeListener(this)
		binding.switchReadingStatusTransparent.setOnCheckedChangeListener(this)
		binding.switchParagraphIndent.setOnCheckedChangeListener(this)
		binding.buttonBookmark.setOnClickListener(this)
		binding.buttonReset.setOnClickListener(this)
		binding.buttonClose.setOnClickListener(this)

		binding.toggleGroupReadingMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
			if (isChecked) {
				settings = settings.copy(
					readingMode = if (checkedId == R.id.btnModeScroll) {
						NovelReadingMode.SCROLL
					} else {
						NovelReadingMode.PAGED
					},
				)
				updatePageTurnAnimationEnabled()
				updatePreviewCard()
				applySettings()
			}
		}

		binding.toggleGroupPageTurnAnimation.addOnButtonCheckedListener { _, checkedId, isChecked ->
			if (isChecked) {
				settings = settings.copy(
					pageTurnAnimation = if (checkedId == R.id.btnAnimationSimulation) {
						NovelPageTurnAnimation.SIMULATION
					} else {
						NovelPageTurnAnimation.SLIDE
					},
				)
				applySettings()
			}
		}

		binding.toggleGroupThemePreset.addOnButtonCheckedListener { _, checkedId, isChecked ->
			if (isChecked) {
				settings = settings.copy(
					themePreset = when (checkedId) {
						R.id.btnThemeSepia -> NovelReaderThemePreset.SEPIA
						R.id.btnThemeMoss -> NovelReaderThemePreset.MOSS
						R.id.btnThemeSlate -> NovelReaderThemePreset.SLATE
						else -> NovelReaderThemePreset.PAPER
					},
				)
				updatePreviewCard()
				applySettings()
			}
		}
	}

	override fun onDestroyView() {
		super.onDestroyView()
		updateRunnable?.let { handler.removeCallbacks(it) }
		updateRunnable = null
		_binding = null
	}

	override fun onValueChange(slider: Slider, value: Float, fromUser: Boolean) {
		if (!fromUser) return

		settings = when (slider.id) {
			R.id.sliderFontSize -> settings.copy(fontSizeSp = value)
			R.id.sliderLineSpacing -> settings.copy(lineSpacing = value)
			R.id.sliderParagraphSpacing -> settings.copy(paragraphSpacing = value)
			R.id.sliderMarginHorizontal -> settings.copy(marginHorizontal = value.toInt())
			R.id.sliderMarginVertical -> settings.copy(marginVertical = value.toInt())
			else -> return
		}.normalized()

		updateValueDisplays()
		updatePreviewCard()
		applySettingsDebounced()
	}

	override fun onCheckedChanged(buttonView: CompoundButton, isChecked: Boolean) {
		when (buttonView.id) {
			R.id.switchDualPage -> settings = settings.copy(enableDualPage = isChecked)
			R.id.switchFullscreen -> settings = settings.copy(enableFullscreen = isChecked)
			R.id.switchShowReadingStatus -> settings = settings.copy(showReadingStatus = isChecked)
			R.id.switchReadingStatusTransparent -> settings = settings.copy(isReadingStatusTransparent = isChecked)
			R.id.switchParagraphIndent -> settings = settings.copy(enableParagraphIndent = isChecked)
			else -> return
		}
		updatePreviewCard()
		applySettings()
	}

	override fun onClick(v: View) {
		when (v.id) {
			R.id.buttonBookmark -> {
				callback?.onBookmarkClick()
				dismiss()
			}

			R.id.buttonReset -> resetSettings()
			R.id.buttonClose -> dismiss()
		}
	}

	private fun applySettings() {
		settings = settings.normalized()
		settings.save(requireContext())
		callback?.onSettingsChanged(settings)
	}

	private fun applySettingsDebounced() {
		updateRunnable?.let { handler.removeCallbacks(it) }
		updateRunnable = Runnable { applySettings() }
		handler.postDelayed(updateRunnable!!, updateDelay)
	}

	private fun resetSettings() {
		settings = NovelReaderSettings().normalized()

		syncControlsFromSettings()
		binding.switchDualPage.isChecked = settings.enableDualPage
		binding.switchFullscreen.isChecked = settings.enableFullscreen
		binding.switchShowReadingStatus.isChecked = settings.showReadingStatus
		binding.switchReadingStatusTransparent.isChecked = settings.isReadingStatusTransparent
		binding.switchParagraphIndent.isChecked = settings.enableParagraphIndent
		binding.toggleGroupReadingMode.check(
			if (settings.readingMode == NovelReadingMode.SCROLL) R.id.btnModeScroll else R.id.btnModePaged,
		)
		binding.toggleGroupPageTurnAnimation.check(
			if (settings.pageTurnAnimation == NovelPageTurnAnimation.SIMULATION) {
				R.id.btnAnimationSimulation
			} else {
				R.id.btnAnimationSlide
			},
		)
		binding.toggleGroupThemePreset.check(
			when (settings.themePreset) {
				NovelReaderThemePreset.PAPER -> R.id.btnThemePaper
				NovelReaderThemePreset.SEPIA -> R.id.btnThemeSepia
				NovelReaderThemePreset.MOSS -> R.id.btnThemeMoss
				NovelReaderThemePreset.SLATE -> R.id.btnThemeSlate
			},
		)

		updateValueDisplays()
		updatePageTurnAnimationEnabled()
		updatePreviewCard()
		applySettings()
	}

	private fun updateValueDisplays() {
		binding.textFontSizeValue.text = String.format("%.1fsp", settings.fontSizeSp)
		binding.textLineSpacingValue.text = String.format("%.1f", settings.lineSpacing)
		binding.textParagraphSpacingValue.text = if (settings.paragraphSpacing <= 0f) {
			getString(R.string.novel_paragraph_spacing_follow_line)
		} else {
			"${settings.paragraphSpacing.toInt()}dp"
		}
		binding.textMarginHorizontalValue.text = "${settings.marginHorizontal}dp"
		binding.textMarginVerticalValue.text = "${settings.marginVertical}dp"
	}

	private fun updatePageTurnAnimationEnabled() {
		binding.toggleGroupPageTurnAnimation.isEnabled = settings.readingMode == NovelReadingMode.PAGED
		for (i in 0 until binding.toggleGroupPageTurnAnimation.childCount) {
			binding.toggleGroupPageTurnAnimation.getChildAt(i).isEnabled =
				settings.readingMode == NovelReadingMode.PAGED
		}
	}

	private fun updatePreviewCard() {
		val palette = novelReaderPalette(
			preset = settings.themePreset,
			isDarkTheme = resources.configuration.uiMode and
				android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
				android.content.res.Configuration.UI_MODE_NIGHT_YES,
		)

		binding.cardPreview.setCardBackgroundColor(palette.backgroundColor)
		binding.cardPreview.strokeColor = ColorUtils.setAlphaComponent(palette.secondaryTextColor, 76)
		binding.cardPreview.strokeWidth = 1

		binding.textPreviewCaption.setTextColor(ColorUtils.setAlphaComponent(palette.secondaryTextColor, 190))
		binding.textPreviewTitle.setTextColor(palette.secondaryTextColor)
		binding.textPreviewBody.setTextColor(palette.textColor)

		binding.textPreviewTitle.textSize = settings.fontSizeSp + 2f
		binding.textPreviewBody.textSize = settings.fontSizeSp

		val bodySpacingExtra = ((settings.lineSpacing - 1f) * binding.textPreviewBody.textSize)
			.coerceAtLeast(0f)
		binding.textPreviewBody.setLineSpacing(bodySpacingExtra, 1f)

		val previewHorizontalPadding = (settings.marginHorizontal * 0.7f).toInt()
		binding.textPreviewBody.setPadding(previewHorizontalPadding, 0, previewHorizontalPadding, 0)

		val paragraphTopMargin = lineSpacingPx()
		binding.textPreviewTitle.updateLayoutParams<ViewGroup.MarginLayoutParams> {
			topMargin = paragraphTopMargin + 2.dpToPx()
		}
		binding.textPreviewBody.updateLayoutParams<ViewGroup.MarginLayoutParams> {
			topMargin = paragraphTopMargin
		}
		binding.textPreviewTitle.requestLayout()
		binding.textPreviewBody.requestLayout()

		val indent = if (settings.enableParagraphIndent) "　　" else ""
		binding.textPreviewBody.text = indent + getString(R.string.novel_preview_body)
	}

	private fun configureSliders() {
		binding.sliderFontSize.apply {
			valueFrom = NovelReaderSettings.FONT_SIZE_RANGE.start
			valueTo = NovelReaderSettings.FONT_SIZE_RANGE.endInclusive
			stepSize = NovelReaderSettings.FONT_SIZE_STEP
		}
		binding.sliderLineSpacing.apply {
			valueFrom = NovelReaderSettings.LINE_SPACING_RANGE.start
			valueTo = NovelReaderSettings.LINE_SPACING_RANGE.endInclusive
			stepSize = NovelReaderSettings.LINE_SPACING_STEP
		}
		binding.sliderParagraphSpacing.apply {
			valueFrom = NovelReaderSettings.PARAGRAPH_SPACING_RANGE.start
			valueTo = NovelReaderSettings.PARAGRAPH_SPACING_RANGE.endInclusive
			stepSize = NovelReaderSettings.PARAGRAPH_SPACING_STEP
		}
		binding.sliderMarginHorizontal.apply {
			valueFrom = NovelReaderSettings.MARGIN_RANGE.first.toFloat()
			valueTo = NovelReaderSettings.MARGIN_RANGE.last.toFloat()
			stepSize = NovelReaderSettings.MARGIN_STEP.toFloat()
		}
		binding.sliderMarginVertical.apply {
			valueFrom = NovelReaderSettings.MARGIN_RANGE.first.toFloat()
			valueTo = NovelReaderSettings.MARGIN_RANGE.last.toFloat()
			stepSize = NovelReaderSettings.MARGIN_STEP.toFloat()
		}
	}

	private fun syncControlsFromSettings() {
		binding.sliderFontSize.value = settings.fontSizeSp
		binding.sliderLineSpacing.value = settings.lineSpacing
		binding.sliderParagraphSpacing.value = settings.paragraphSpacing
		binding.sliderMarginHorizontal.value = settings.marginHorizontal.toFloat()
		binding.sliderMarginVertical.value = settings.marginVertical.toFloat()
	}

	private fun lineSpacingPx(): Int {
		return ((settings.lineSpacing - 1f).coerceAtLeast(0f) * binding.textPreviewBody.textSize).toInt()
	}

	private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

	interface Callback {

		fun onSettingsChanged(settings: NovelReaderSettings)

		fun onBookmarkClick()
	}

	companion object {

		fun newInstance(): NovelReaderConfigSheet = NovelReaderConfigSheet()
	}
}
