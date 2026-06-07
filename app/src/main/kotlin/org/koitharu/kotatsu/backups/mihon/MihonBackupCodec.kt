package org.koitharu.kotatsu.backups.mihon

import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Reads/writes Mihon `.tachibk` backups: gzip-compressed kotlinx-serialization ProtoBuf.
 */
object MihonBackupCodec {

	private val protoBuf = ProtoBuf { }

	fun decode(input: InputStream): MihonBackup {
		val bytes = GZIPInputStream(input).use { it.readBytes() }
		return protoBuf.decodeFromByteArray(bytes)
	}

	fun encode(backup: MihonBackup, output: OutputStream) {
		val bytes = protoBuf.encodeToByteArray(backup)
		GZIPOutputStream(output).use { it.write(bytes) }
	}
}
