package org.koitharu.kotatsu.core.parser.mihon

import java.io.IOException

/** Contain extension ABI/initializer failures outside OkHttp as ordinary, copyable source errors. */
internal fun mihonRuntimeFailure(error: Throwable): Throwable = when (error) {
    is LinkageError -> IOException("Extension runtime error: ${error.message ?: error.javaClass.simpleName}", error)
    else -> error
}

internal inline fun <T> mihonExtensionCall(block: () -> T): T = try {
    block()
} catch (error: LinkageError) {
    throw mihonRuntimeFailure(error)
}
