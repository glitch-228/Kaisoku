package org.koitharu.kotatsu.core.parser.lnreader

/**
 * JVM-testable logging for the LNReader engine layer.
 * android.util.Log is not mocked in local unit tests, so this shim routes to
 * java.util.logging which works on both the JVM and Android.
 */
internal object LnLog {

	private val logger = java.util.logging.Logger.getLogger("LNReader")

	fun d(tag: String, message: String) = logger.fine("[$tag] $message")

	fun w(tag: String, message: String) = logger.warning("[$tag] $message")

	fun e(tag: String, message: String, error: Throwable? = null) {
		if (error != null) {
			logger.severe("[$tag] $message: ${error.message}")
		} else {
			logger.severe("[$tag] $message")
		}
	}
}
