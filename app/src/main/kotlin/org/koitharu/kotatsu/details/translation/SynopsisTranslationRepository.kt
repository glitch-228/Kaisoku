package org.koitharu.kotatsu.details.translation

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.koitharu.kotatsu.reader.translate.MultimodalTranslator
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SynopsisTranslationRepository @Inject constructor(
    @ApplicationContext context: Context,
    translator: MultimodalTranslator,
) {
    internal val service = SynopsisTranslationService(
        FileSynopsisTranslationCache(File(context.cacheDir, "synopsis_translations")),
        translator::translateText,
    )
}
