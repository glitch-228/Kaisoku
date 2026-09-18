package org.koitharu.kotatsu.core.exceptions

import java.io.IOException

internal class CbrImportException(
	val reason: Reason,
	cause: Throwable? = null,
) : IOException(cause?.message, cause) {

	enum class Reason {
		CORRUPTED,
		EMPTY,
		ENCRYPTED,
		MULTIPART,
		NO_SPACE,
		TOO_LARGE_DICTIONARY,
		UNSAFE,
		UNSUPPORTED,
	}
}
