package org.koitharu.kotatsu.core.parser.mihon

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class MihonRuntimeFailureTest {
    @Test
    fun sourceInitializerAndMissingRuntimeSymbolsKeepTheirCopyableCauses() {
        for (cause in listOf(
            ExceptionInInitializerError(IllegalStateException("No registered ProtoBuf")),
            NoClassDefFoundError("Could not initialize extension"),
            NoSuchMethodError("missing host ABI"),
        )) {
            val failure = assertThrows(IOException::class.java) { mihonExtensionCall { throw cause } }
            assertSame(cause, failure.cause)
        }
    }

    @Test
    fun cancellationAndExistingExceptionsAreNotWrapped() {
        for (cause in listOf(CancellationException("source cancelled"), IOException("source failure"),
            InterruptedException("source interrupted"))) {
            assertSame(cause, assertThrows(Exception::class.java) { mihonExtensionCall { throw cause } })
            assertSame(cause, mihonRuntimeFailure(cause))
        }
    }

    @Test
    fun fatalVmErrorsAndAssertionsAreNotHiddenAsSourceFailures() {
        for (cause in listOf(OutOfMemoryError("fixture"), StackOverflowError("fixture"), AssertionError("fixture"))) {
            assertSame(cause, assertThrows(Error::class.java) { mihonExtensionCall { throw cause } })
            assertSame(cause, mihonRuntimeFailure(cause))
        }
    }
}
