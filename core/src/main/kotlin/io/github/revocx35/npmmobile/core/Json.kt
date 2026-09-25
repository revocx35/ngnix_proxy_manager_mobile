package io.github.revocx35.npmmobile.core

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * JSON settings shared by the client and the tests.
 *
 * - Kotlin properties are camelCase and map to NPM's snake_case keys (`http2Support` -> `http2_support`);
 *   a key the strategy can't produce gets an explicit `@SerialName` (e.g. `requires_2fa`).
 *
 * - Unknown keys are ignored: NPM adds fields between releases and the app must keep working.
 * - Explicit nulls are not written: request bodies are validated by NPM with `additionalProperties: false`
 *   and several fields reject `null`, so optional request fields are left out instead.
 */
@OptIn(ExperimentalSerializationApi::class)
val NpmJson: Json = Json {
    namingStrategy = JsonNamingStrategy.SnakeCase
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    coerceInputValues = true
    isLenient = true
}

/**
 * NPM stores booleans in SQLite/MySQL and older releases (or custom builds) sometimes return them as 0/1.
 * Accept true/false, 0/1 and "true"/"false"/"1"/"0"; anything else (including null) becomes false.
 */
object LenientBooleanSerializer : KSerializer<Boolean> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LenientBoolean", PrimitiveKind.BOOLEAN)

    override fun serialize(encoder: Encoder, value: Boolean) = encoder.encodeBoolean(value)

    override fun deserialize(decoder: Decoder): Boolean {
        val element = (decoder as? JsonDecoder)?.decodeJsonElement() ?: return decoder.decodeBoolean()
        if (element !is JsonPrimitive || element is JsonNull) return false
        element.booleanOrNull?.let { return it }
        element.intOrNull?.let { return it != 0 }
        return when (element.content.lowercase()) {
            "true", "1", "yes" -> true
            else -> false
        }
    }
}

/** A Boolean that tolerates 0/1 in responses (see [LenientBooleanSerializer]). */
typealias NBool = @Serializable(with = LenientBooleanSerializer::class) Boolean

/**
 * The `certificate_id` field of a host: 0 = no certificate, a positive id = an existing certificate,
 * "new" = ask NPM to request a Let's Encrypt certificate for the host's domains while saving it.
 */
@Serializable(with = CertificateRefSerializer::class)
sealed interface CertificateRef {
    data object None : CertificateRef
    data object RequestNew : CertificateRef
    data class Existing(val id: Int) : CertificateRef

    val idOrZero: Int get() = (this as? Existing)?.id ?: 0

    companion object {
        fun of(id: Int): CertificateRef = if (id > 0) Existing(id) else None
    }
}

object CertificateRefSerializer : KSerializer<CertificateRef> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("CertificateRef", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: CertificateRef) {
        val json = encoder as? JsonEncoder ?: throw SerializationException("CertificateRef needs JSON")
        json.encodeJsonElement(
            when (value) {
                CertificateRef.None -> JsonPrimitive(0)
                CertificateRef.RequestNew -> JsonPrimitive("new")
                is CertificateRef.Existing -> JsonPrimitive(value.id)
            },
        )
    }

    override fun deserialize(decoder: Decoder): CertificateRef {
        val element = (decoder as? JsonDecoder)?.decodeJsonElement() ?: return CertificateRef.of(decoder.decodeInt())
        if (element !is JsonPrimitive || element is JsonNull) return CertificateRef.None
        if (element.content == "new") return CertificateRef.RequestNew
        return CertificateRef.of(element.content.toIntOrNull() ?: 0)
    }
}
