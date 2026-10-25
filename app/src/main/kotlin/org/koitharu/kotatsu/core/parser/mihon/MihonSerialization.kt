package org.koitharu.kotatsu.core.parser.mihon

import kotlinx.serialization.SerialFormat
import kotlinx.serialization.StringFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.TypeReference
import java.lang.reflect.Type

/** These public class keys must match requests made by separately compiled extensions. */
internal fun InjektRegistrar.registerMihonSerialization() {
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
    addSingleton(serializationType<Json>(Json::class.java), json)
    addSingleton(serializationType<StringFormat>(StringFormat::class.java), json)
    addSingleton(serializationType<SerialFormat>(SerialFormat::class.java), json)
    // ProtoBuf.Default is a subclass. Registering its inferred type leaves Injekt.get<ProtoBuf>() unbound.
    addSingleton(serializationType<ProtoBuf>(ProtoBuf::class.java), ProtoBuf)
}

private fun <T> serializationType(type: Type): TypeReference<T> = object : TypeReference<T> {
    override val type: Type = type
}
