package org.koitharu.kotatsu.core.model

import android.content.Context
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.style.ImageSpan
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.text.inSpans
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.customsource.data.CustomSourcesRepository
import org.koitharu.kotatsu.customsource.domain.CustomMangaSource
import org.koitharu.kotatsu.core.parser.external.ExternalMangaSource
import org.koitharu.kotatsu.core.parser.lnreader.LnReaderMangaSource
import org.koitharu.kotatsu.core.parser.lnreader.LnReaderSourceRegistry
import org.koitharu.kotatsu.core.parser.lnreader.UnresolvedLnReaderSource
import org.koitharu.kotatsu.core.parser.mihon.MihonMangaSource
import org.koitharu.kotatsu.core.util.ext.getDisplayName
import org.koitharu.kotatsu.core.util.ext.toLocale
import org.koitharu.kotatsu.core.util.ext.toLocaleOrNull
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.util.splitTwoParts
import java.util.Locale

data object LocalMangaSource : MangaSource {
	override val name = "LOCAL"
}

data object UnknownMangaSource : MangaSource {
	override val name = "UNKNOWN"
}

data object TestMangaSource : MangaSource {
	override val name = "TEST"
}

fun MangaSource(name: String?): MangaSource {
	when (name ?: return UnknownMangaSource) {
		UnknownMangaSource.name -> return UnknownMangaSource
		LocalMangaSource.name -> return LocalMangaSource
		TestMangaSource.name -> return TestMangaSource
	}
	if (name.startsWith("content:")) {
		val parts = name.substringAfter(':').splitTwoParts('/') ?: return UnknownMangaSource
		return ExternalMangaSource(packageName = parts.first, authority = parts.second)
	}
	if (name.startsWith("mihon:")) {
		val parts = name.substringAfter(':').splitTwoParts('/') ?: return UnknownMangaSource
		return MihonMangaSource(
			packageName = parts.first,
			sourceId = parts.second.toLongOrNull() ?: return UnknownMangaSource,
		)
	}
	if (name.startsWith(LnReaderMangaSource.NAME_PREFIX)) {
		val pluginId = LnReaderMangaSource.extractPluginId(name) ?: return UnknownMangaSource
		LnReaderSourceRegistry.peek(pluginId)?.let { return it }
		return UnresolvedLnReaderSource(pluginId)
	}
	if (name.startsWith(MihonMangaSource.TACHI_IDENTIFIER_PREFIX)) {
		val packageName = name.substringAfter('_')
		val match = org.koitharu.kotatsu.core.parser.mihon.MihonSourceRegistry
			.findByPackageName(packageName).firstOrNull()
		if (match != null) {
			return match
		}
		return MihonMangaSource(packageName = packageName, sourceId = -1)
	}
	if (name.startsWith(CustomMangaSource.NAME_PREFIX)) {
		val id = CustomMangaSource.extractId(name)
		val cs = id?.let { CustomSourcesRepository.peekById(it) }
		return if (cs != null) CustomMangaSource(cs) else UnresolvedMangaSource(name)
	}
	if (':' in name) {
		MangaSourceRegistry.resolve(name)?.let {
			return it
		}
	}
	MangaParserSource.entries.forEach {
		if (it.name == name) return it
	}
	MangaSourceRegistry.resolve(name)?.let {
		return it
	}
	return UnresolvedMangaSource(name)
}

fun Collection<String>.toMangaSources() = map(::MangaSource)

/**
 * Effective NSFW state of a source: a manual override if the user set one, the intrinsic
 * rating otherwise.
 */
fun MangaSource.isNsfw(): Boolean {
	val source = unwrap()
	return NsfwSourceOverrides.peek(source.name) ?: source.intrinsicIsNsfw()
}

/**
 * NSFW state declared by the source itself - its parser content type, or the NSFW flag of the
 * extension/plugin providing it. Ignores any manual override.
 */
fun MangaSource.intrinsicIsNsfw(): Boolean = when (val source = unwrap()) {
	is MangaParserSource -> source.contentType == ContentType.HENTAI
	is PluginMangaSource -> source.contentType == ContentType.HENTAI
	is MihonMangaSource -> source.resolved().isNsfwSource
	is ExternalMangaSource -> source.isNsfwSource
	else -> false
}

/**
 * True when the user manually marked this source as NSFW or SFW, overriding [intrinsicIsNsfw].
 */
fun MangaSource.hasNsfwOverride(): Boolean = NsfwSourceOverrides.peek(unwrap().name) != null

@get:StringRes
val ContentType.titleResId
	get() = when (this) {
		ContentType.MANGA -> R.string.content_type_manga
		ContentType.HENTAI -> R.string.content_type_hentai
		ContentType.COMICS -> R.string.content_type_comics
		ContentType.OTHER -> R.string.content_type_other
		ContentType.MANHWA -> R.string.content_type_manhwa
		ContentType.MANHUA -> R.string.content_type_manhua
		ContentType.NOVEL -> R.string.content_type_novel
		ContentType.ONE_SHOT -> R.string.content_type_one_shot
		ContentType.DOUJINSHI -> R.string.content_type_doujinshi
		ContentType.IMAGE_SET -> R.string.content_type_image_set
		ContentType.ARTIST_CG -> R.string.content_type_artist_cg
		ContentType.GAME_CG -> R.string.content_type_game_cg
	}

tailrec fun MangaSource.unwrap(): MangaSource = if (this is MangaSourceInfo) {
	mangaSource.unwrap()
} else {
	this
}

fun MangaSource.getLocale(): Locale? = when (val source = unwrap()) {
	is MangaParserSource -> source.locale.toLocaleOrNull()
	is PluginMangaSource -> source.locale.toLocaleOrNull()
	is MihonMangaSource -> source.resolved().locale?.toLocaleOrNull()
	is LnReaderMangaSource -> source.lang?.toLocaleOrNull()
	else -> null
}

/**
 * True when this source is provided by an *external* plugin/extension rather than by the app:
 * the Content-Provider plugin flow (ExternalMangaSource) or the in-app Mihon bridge (MihonMangaSource).
 * Such sources have scrobble/control surfaces that live outside standard source-settings patterns.
 */
fun MangaSource.isExternalSource(): Boolean = when (unwrap()) {
	is ExternalMangaSource, is MihonMangaSource -> true
	else -> false
}

fun MangaSource.externalPackageName(): String? = when (val source = unwrap()) {
	is ExternalMangaSource -> source.packageName
	is MihonMangaSource -> source.packageName
	else -> null
}

fun MangaSource.identityName(): String = when (val name = unwrap().name) {
	MangaParserSource.MANGA_OVH_UPDATES.name -> MangaParserSource.MANGA_OVH.name
	else -> name
}

fun MangaSource.getSummary(context: Context): String? = when (val source = unwrap()) {
	is LnReaderMangaSource -> source.site?.toUri()?.host

	is MangaParserSource -> {
		val type = context.getString(source.contentType.titleResId)
		val locale = source.locale.toLocale().getDisplayName(context)
		context.getString(R.string.source_summary_pattern, type, locale)
	}

	is PluginMangaSource -> {
		val type = context.getString(source.contentType.titleResId)
		val locale = source.locale.toLocaleOrNull()?.getDisplayName(context)
		val baseSummary = if (locale != null) {
			context.getString(R.string.source_summary_pattern, type, locale)
		} else {
			type
		}
		"$baseSummary • ${source.jarName}"
	}

	is MihonMangaSource -> source.resolved().locale?.toLocaleOrNull()?.let { locale ->
		context.getString(
			R.string.source_summary_pattern,
			context.getString(R.string.mihon_source),
			locale.getDisplayName(context),
		)
	} ?: context.getString(R.string.mihon_source)

	is ExternalMangaSource -> context.getString(R.string.external_source)

	is CustomMangaSource -> source.source.baseUrl.toUri().host ?: source.source.cleanBaseUrl

	else -> null
}

fun MangaSource.getTitle(context: Context): String = when (val source = unwrap()) {
	is MangaParserSource -> source.title
	is LnReaderMangaSource -> source.displayName
	is UnresolvedLnReaderSource -> source.pluginId
	is CustomMangaSource -> source.displayTitle
	is PluginMangaSource -> source.title
	is MihonMangaSource -> source.resolveName(context)
	LocalMangaSource -> context.getString(R.string.local_storage)
	TestMangaSource -> context.getString(R.string.test_parser)
	is ExternalMangaSource -> source.resolveName(context)
	is UnresolvedMangaSource -> source.name
	else -> context.getString(R.string.unknown)
}

fun SpannableStringBuilder.appendIcon(textView: TextView, @DrawableRes resId: Int): SpannableStringBuilder {
	val icon = ContextCompat.getDrawable(textView.context, resId) ?: return this
	icon.setTintList(textView.textColors)
	val size = textView.lineHeight
	icon.setBounds(0, 0, size, size)
	val alignment = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
		ImageSpan.ALIGN_CENTER
	} else {
		ImageSpan.ALIGN_BOTTOM
	}
	return inSpans(ImageSpan(icon, alignment)) { append(' ') }
}
