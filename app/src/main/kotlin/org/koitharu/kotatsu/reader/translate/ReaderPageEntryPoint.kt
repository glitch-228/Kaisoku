package org.koitharu.kotatsu.reader.translate

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ActivityComponent
import org.koitharu.kotatsu.core.prefs.AppSettings

/** Hilt entry point for ViewHolders that can't be Hilt-injected directly. */
@EntryPoint
@InstallIn(ActivityComponent::class)
interface ReaderPageEntryPoint {
	fun translationCoordinator(): TranslationCoordinator
	fun appSettings(): AppSettings
}
