package org.koitharu.kotatsu.core.parser.mihon

import kotlinx.serialization.SerialFormat
import kotlinx.serialization.Serializable
import kotlinx.serialization.StringFormat
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.InjektionException
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.registry.default.DefaultRegistrar

class MihonSerializationTest {
    @Test
    fun extensionInitializerReproducesTheMissingBindingBeforeRegistration() {
        val failure = assertThrows(ExceptionInInitializerError::class.java) { MissingBindingExtension() }
        assertTrue(failure.cause is InjektionException)
        assertTrue(failure.cause!!.message.orEmpty().contains("kotlinx.serialization.protobuf.ProtoBuf"))
    }

    @Test
    fun registeredExtensionInitializerResolvesThePublicProtoBufClass() {
        assertSame(ProtoBuf, RegisteredExtension.format)
        val original = Payload(365, "source payload")
        val bytes = RegisteredExtension.format.encodeToByteArray(original)
        assertEquals(original, RegisteredExtension.format.decodeFromByteArray<Payload>(bytes))
    }

    @Test
    fun registrationPreservesJsonAndItsExistingAliases() {
        val scope = registeredScope()
        val json = scope.get<Json>()
        assertSame(json, scope.get<StringFormat>())
        assertSame(json, scope.get<SerialFormat>())
        assertSame(ProtoBuf, scope.get<ProtoBuf>())
        assertTrue(json.configuration.ignoreUnknownKeys)
        assertTrue(!json.configuration.explicitNulls)
    }

    @Serializable
    private data class Payload(val chapter: Int, val text: String)

    private class MissingBindingExtension {
        companion object {
            val format: ProtoBuf = InjektScope(DefaultRegistrar()).get()
        }
    }

    private class RegisteredExtension {
        companion object {
            val format: ProtoBuf = registeredScope().get()
        }
    }

    companion object {
        private fun registeredScope() = InjektScope(DefaultRegistrar()).apply {
            importModule(object : InjektModule {
                override fun InjektRegistrar.registerInjectables() = registerMihonSerialization()
            })
        }
    }
}
