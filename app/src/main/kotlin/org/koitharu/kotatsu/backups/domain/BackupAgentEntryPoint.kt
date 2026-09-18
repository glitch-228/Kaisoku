package org.koitharu.kotatsu.backups.domain

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.koitharu.kotatsu.core.model.NsfwOverridesLoader
import org.koitharu.kotatsu.core.parser.lnreader.LnReaderSourceManager
import org.koitharu.kotatsu.core.parser.mihon.MihonExtensionManager
import org.koitharu.kotatsu.community.data.CommunityRepository

@EntryPoint
@InstallIn(SingletonComponent::class)
interface BackupAgentEntryPoint {
	val mihonExtensionManager: MihonExtensionManager
	val nsfwOverridesLoader: NsfwOverridesLoader
	val lnReaderSourceManager: LnReaderSourceManager
	val communityRepository: CommunityRepository
}
