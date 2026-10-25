package org.koitharu.kotatsu.scrobbling.v2

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.koitharu.kotatsu.scrobbling.v2.categories.CategoryStatusAccess

@Module
@InstallIn(SingletonComponent::class)
abstract class TrackerWorkflowModule {
    @Binds abstract fun categoryAccess(implementation: TrackerCategoryAccess): CategoryStatusAccess
    @Binds abstract fun outgoingAccess(implementation:
        org.koitharu.kotatsu.scrobbling.v2.progress.TrackerOutgoingAccess):
        org.koitharu.kotatsu.scrobbling.v2.progress.OutgoingProgressAccess
}
