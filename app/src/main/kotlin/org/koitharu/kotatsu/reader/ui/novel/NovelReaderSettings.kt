/*
 * Novel reader core, ported from Kototoro (Apache-2.0).
 * Copyright 2025 Kototoro contributors.
 * Copyright 2026 Kaisoku contributors.
 */
package org.koitharu.kotatsu.reader.ui.novel

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

data class NovelReaderSettings(
	val fontSizeSp: Float = 17f,
	val lineSpacing: Float = 1.6f,
	val paragraphSpacing: Float = 0f,
	val marginHorizontal: Int = 36,
	val marginVertical: Int = 36,
	val themePreset: NovelReaderThemePreset = NovelReaderThemePreset.PAPER,
	val readingMode: NovelReadingMode = NovelReadingMode.PAGED,
	val pageTurnAnimation: NovelPageTurnAnimation = NovelPageTurnAnimation.SLIDE,
	val enableDualPage: Boolean = true,
	val enableFullscreen: Boolean = false,
	val showReadingStatus: Boolean = true,
	val isReadingStatusTransparent: Boolean = true,
	val enableParagraphIndent: Boolean = true,
) {

	fun normalized(): NovelReaderSettings = copy(
		fontSizeSp = snapFloat(fontSizeSp, FONT_SIZE_RANGE.start, FONT_SIZE_RANGE.endInclusive, FONT_SIZE_STEP),
		lineSpacing = snapFloat(lineSpacing, LINE_SPACING_RANGE.start, LINE_SPACING_RANGE.endInclusive, LINE_SPACING_STEP),
		paragraphSpacing = snapFloat(
			paragraphSpacing,
			PARAGRAPH_SPACING_RANGE.start,
			PARAGRAPH_SPACING_RANGE.endInclusive,
			PARAGRAPH_SPACING_STEP,
		),
		marginHorizontal = snapInt(marginHorizontal, MARGIN_RANGE.first, MARGIN_RANGE.last, MARGIN_STEP),
		marginVertical = snapInt(marginVertical, MARGIN_RANGE.first, MARGIN_RANGE.last, MARGIN_STEP),
	)

	fun save(context: Context) {
		val normalized = normalized()
		getPrefs(context).edit {
			putFloat(KEY_FONT_SIZE, normalized.fontSizeSp)
			putFloat(KEY_LINE_SPACING, normalized.lineSpacing)
			putFloat(KEY_PARAGRAPH_SPACING, normalized.paragraphSpacing)
			putInt(KEY_MARGIN_HORIZONTAL, normalized.marginHorizontal)
			putInt(KEY_MARGIN_VERTICAL, normalized.marginVertical)
			putString(KEY_THEME_PRESET, normalized.themePreset.name)
			putString(KEY_READING_MODE, normalized.readingMode.name)
			putString(KEY_PAGE_TURN_ANIMATION, normalized.pageTurnAnimation.name)
			putBoolean(KEY_DUAL_PAGE, normalized.enableDualPage)
			putBoolean(KEY_FULLSCREEN, normalized.enableFullscreen)
			putBoolean(KEY_SHOW_READING_STATUS, normalized.showReadingStatus)
			putBoolean(KEY_READING_STATUS_TRANSPARENT, normalized.isReadingStatusTransparent)
			putBoolean(KEY_PARAGRAPH_INDENT, normalized.enableParagraphIndent)
		}
	}

	companion object {

		const val FONT_SIZE_STEP = 0.5f
		const val LINE_SPACING_STEP = 0.1f
		const val PARAGRAPH_SPACING_STEP = 1f
		const val MARGIN_STEP = 4
		val FONT_SIZE_RANGE = 14f..24f
		val LINE_SPACING_RANGE = 1.2f..2.0f
		val PARAGRAPH_SPACING_RANGE = 0f..24f
		val MARGIN_RANGE = 12..120

		private const val PREF_NAME = "novel_reader_settings"
		private const val KEY_FONT_SIZE = "font_size"
		private const val KEY_LINE_SPACING = "line_spacing"
		private const val KEY_PARAGRAPH_SPACING = "paragraph_spacing"
		private const val KEY_MARGIN_HORIZONTAL = "margin_horizontal"
		private const val KEY_MARGIN_VERTICAL = "margin_vertical"
		private const val KEY_THEME_PRESET = "theme_preset"
		private const val KEY_READING_MODE = "reading_mode"
		private const val KEY_PAGE_TURN_ANIMATION = "page_turn_animation"
		private const val KEY_DUAL_PAGE = "dual_page"
		private const val KEY_FULLSCREEN = "fullscreen"
		private const val KEY_SHOW_READING_STATUS = "show_reading_status"
		private const val KEY_READING_STATUS_TRANSPARENT = "reading_status_transparent"
		private const val KEY_PARAGRAPH_INDENT = "paragraph_indent"

		fun load(context: Context): NovelReaderSettings {
			val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
			return NovelReaderSettings(
				fontSizeSp = prefs.getFloat(KEY_FONT_SIZE, 17f),
				lineSpacing = prefs.getFloat(KEY_LINE_SPACING, 1.6f),
				paragraphSpacing = prefs.getFloat(KEY_PARAGRAPH_SPACING, 0f),
				marginHorizontal = prefs.getInt(KEY_MARGIN_HORIZONTAL, 36),
				marginVertical = prefs.getInt(KEY_MARGIN_VERTICAL, 36),
				themePreset = prefs.getString(KEY_THEME_PRESET, null).parseEnum(NovelReaderThemePreset.PAPER),
				readingMode = prefs.getString(KEY_READING_MODE, null).parseEnum(NovelReadingMode.PAGED),
				pageTurnAnimation = prefs.getString(KEY_PAGE_TURN_ANIMATION, null)
					.parseEnum(NovelPageTurnAnimation.SLIDE),
				enableDualPage = prefs.getBoolean(KEY_DUAL_PAGE, true),
				enableFullscreen = prefs.getBoolean(KEY_FULLSCREEN, false),
				showReadingStatus = prefs.getBoolean(KEY_SHOW_READING_STATUS, true),
				isReadingStatusTransparent = prefs.getBoolean(KEY_READING_STATUS_TRANSPARENT, true),
				enableParagraphIndent = prefs.getBoolean(KEY_PARAGRAPH_INDENT, true),
			).normalized()
		}

		private inline fun <reified T : Enum<T>> String?.parseEnum(default: T): T {
			if (this == null) return default
			return runCatching { enumValueOf<T>(this) }.getOrDefault(default)
		}

		private fun getPrefs(context: Context): SharedPreferences =
			context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

		private fun snapFloat(value: Float, min: Float, max: Float, step: Float): Float {
			val clamped = value.coerceIn(min, max)
			val snappedSteps = ((clamped - min) / step).toInt()
			val remainder = (clamped - min) - (snappedSteps * step)
			val rounded = if (remainder >= step / 2f) snappedSteps + 1 else snappedSteps
			return (min + rounded * step).coerceIn(min, max)
		}

		private fun snapInt(value: Int, min: Int, max: Int, step: Int): Int {
			val clamped = value.coerceIn(min, max)
			val snappedSteps = (clamped - min) / step
			val remainder = (clamped - min) % step
			val rounded = if (remainder >= step / 2f) snappedSteps + 1 else snappedSteps
			return (min + rounded * step).coerceIn(min, max)
		}
	}
}

enum class NovelReaderThemePreset {
	PAPER,
	SEPIA,
	MOSS,
	SLATE,
}

enum class NovelPageTurnAnimation {
	SLIDE,
	SIMULATION,
}

enum class NovelReadingMode {
	PAGED,
	SCROLL,
}

data class NovelReaderPalette(
	val backgroundColor: Int,
	val textColor: Int,
	val secondaryTextColor: Int,
	val chromeBackgroundColor: Int,
	val chromeTextColor: Int,
	val highlightColor: Int,
	val placeholderColor: Int,
	val placeholderTextColor: Int,
	val isDark: Boolean,
)

fun novelReaderPalette(
	preset: NovelReaderThemePreset,
	isDarkTheme: Boolean,
): NovelReaderPalette = when (preset) {
	NovelReaderThemePreset.PAPER -> if (isDarkTheme) {
		NovelReaderPalette(
			backgroundColor = 0xFF221F1B.toInt(),
			textColor = 0xFFDED6C9.toInt(),
			secondaryTextColor = 0xFFB7AEA0.toInt(),
			chromeBackgroundColor = 0xFF2D2924.toInt(),
			chromeTextColor = 0xFFE6DED1.toInt(),
			highlightColor = 0x4DB68A4A,
			placeholderColor = 0xFF3B352F.toInt(),
			placeholderTextColor = 0xFFB7AEA0.toInt(),
			isDark = true,
		)
	} else {
		NovelReaderPalette(
			backgroundColor = 0xFFF4ECD8.toInt(),
			textColor = 0xFF4F4032.toInt(),
			secondaryTextColor = 0xFF7A6A59.toInt(),
			chromeBackgroundColor = 0xFFE7DDC5.toInt(),
			chromeTextColor = 0xFF544436.toInt(),
			highlightColor = 0x4DA67C2E,
			placeholderColor = 0xFFDDD2BC.toInt(),
			placeholderTextColor = 0xFF7A6A59.toInt(),
			isDark = false,
		)
	}

	NovelReaderThemePreset.SEPIA -> if (isDarkTheme) {
		NovelReaderPalette(
			backgroundColor = 0xFF2A241D.toInt(),
			textColor = 0xFFE4D4B8.toInt(),
			secondaryTextColor = 0xFFB9A58A.toInt(),
			chromeBackgroundColor = 0xFF342D25.toInt(),
			chromeTextColor = 0xFFEFDFC2.toInt(),
			highlightColor = 0x4DBA8748,
			placeholderColor = 0xFF43382D.toInt(),
			placeholderTextColor = 0xFFB9A58A.toInt(),
			isDark = true,
		)
	} else {
		NovelReaderPalette(
			backgroundColor = 0xFFF1E4CC.toInt(),
			textColor = 0xFF5A4330.toInt(),
			secondaryTextColor = 0xFF8A7057.toInt(),
			chromeBackgroundColor = 0xFFE4D3B7.toInt(),
			chromeTextColor = 0xFF614734.toInt(),
			highlightColor = 0x4DA87B2C,
			placeholderColor = 0xFFDCC7A6.toInt(),
			placeholderTextColor = 0xFF8A7057.toInt(),
			isDark = false,
		)
	}

	NovelReaderThemePreset.MOSS -> if (isDarkTheme) {
		NovelReaderPalette(
			backgroundColor = 0xFF1F2520.toInt(),
			textColor = 0xFFD5DDD2.toInt(),
			secondaryTextColor = 0xFFA5B19E.toInt(),
			chromeBackgroundColor = 0xFF293029.toInt(),
			chromeTextColor = 0xFFE0E7DB.toInt(),
			highlightColor = 0x4D7DA062,
			placeholderColor = 0xFF333B34.toInt(),
			placeholderTextColor = 0xFFA5B19E.toInt(),
			isDark = true,
		)
	} else {
		NovelReaderPalette(
			backgroundColor = 0xFFE6E9DB.toInt(),
			textColor = 0xFF36402E.toInt(),
			secondaryTextColor = 0xFF61705A.toInt(),
			chromeBackgroundColor = 0xFFD6DBC8.toInt(),
			chromeTextColor = 0xFF3D4735.toInt(),
			highlightColor = 0x4D6F8A42,
			placeholderColor = 0xFFC8D0BA.toInt(),
			placeholderTextColor = 0xFF61705A.toInt(),
			isDark = false,
		)
	}

	NovelReaderThemePreset.SLATE -> if (isDarkTheme) {
		NovelReaderPalette(
			backgroundColor = 0xFF1D2329.toInt(),
			textColor = 0xFFD7DDE2.toInt(),
			secondaryTextColor = 0xFFAEB8C0.toInt(),
			chromeBackgroundColor = 0xFF252D34.toInt(),
			chromeTextColor = 0xFFE3E8EC.toInt(),
			highlightColor = 0x4D5F8EAD,
			placeholderColor = 0xFF313A42.toInt(),
			placeholderTextColor = 0xFFAEB8C0.toInt(),
			isDark = true,
		)
	} else {
		NovelReaderPalette(
			backgroundColor = 0xFFE8EDF1.toInt(),
			textColor = 0xFF3A4650.toInt(),
			secondaryTextColor = 0xFF66737D.toInt(),
			chromeBackgroundColor = 0xFFDCE4EA.toInt(),
			chromeTextColor = 0xFF404B54.toInt(),
			highlightColor = 0x4D6D93B0,
			placeholderColor = 0xFFCED8DF.toInt(),
			placeholderTextColor = 0xFF66737D.toInt(),
			isDark = false,
		)
	}
}
