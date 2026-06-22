package org.koitharu.kotatsu.reader.translate

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import kotlinx.serialization.protobuf.ProtoBuf

/**
 * Minimal protobuf mirror of the Google Lens "lens overlay" upload API used by Chromium's
 * keyless Lens OCR (see `dimdenGD/chrome-lens-ocr`). Only the fields we read/write are declared;
 * unknown fields are skipped on decode. Field numbers + enum values were taken from the upstream
 * generated descriptors. Boxes come back as normalized centre-rotated boxes (0..1).
 */
@OptIn(ExperimentalSerializationApi::class)
internal object LensProtocol {

	// ---------- request ----------
	@Serializable
	data class ServerRequest(@ProtoNumber(1) val objectsRequest: ObjectsRequest? = null)

	@Serializable
	data class ObjectsRequest(
		@ProtoNumber(1) val requestContext: RequestContext? = null,
		@ProtoNumber(3) val imageData: ImageData? = null,
	)

	@Serializable
	data class RequestContext(
		@ProtoNumber(3) val requestId: RequestId? = null,
		@ProtoNumber(4) val clientContext: ClientContext? = null,
	)

	@Serializable
	data class RequestId(
		@ProtoNumber(1) val uuid: Long = 0,
		@ProtoNumber(2) val sequenceId: Long = 0,
		@ProtoNumber(3) val imageSequenceId: Long = 0,
	)

	@Serializable
	data class ClientContext(
		@ProtoNumber(1) val platform: Int = 0,
		@ProtoNumber(2) val surface: Int = 0,
		@ProtoNumber(4) val localeContext: LocaleContext? = null,
		@ProtoNumber(17) val clientFilters: AppliedFilters? = null,
	)

	@Serializable
	data class LocaleContext(
		@ProtoNumber(1) val language: String = "",
		@ProtoNumber(2) val region: String = "",
		@ProtoNumber(3) val timeZone: String = "",
	)

	@Serializable
	data class AppliedFilters(@ProtoNumber(1) val filter: List<AppliedFilter> = emptyList())

	@Serializable
	data class AppliedFilter(@ProtoNumber(1) val filterType: Int = 0)

	@Serializable
	data class ImageData(
		@ProtoNumber(1) val payload: ImagePayload? = null,
		@ProtoNumber(3) val imageMetadata: ImageMetadata? = null,
	)

	@Serializable
	data class ImagePayload(@ProtoNumber(1) val imageBytes: ByteArray = ByteArray(0))

	@Serializable
	data class ImageMetadata(
		@ProtoNumber(1) val width: Int = 0,
		@ProtoNumber(2) val height: Int = 0,
	)

	// ---------- response ----------
	@Serializable
	data class ServerResponse(@ProtoNumber(2) val objectsResponse: ObjectsResponse? = null)

	@Serializable
	data class ObjectsResponse(@ProtoNumber(3) val text: Text? = null)

	@Serializable
	data class Text(
		@ProtoNumber(1) val textLayout: TextLayout? = null,
		@ProtoNumber(2) val contentLanguage: String = "",
	)

	@Serializable
	data class TextLayout(@ProtoNumber(1) val paragraphs: List<Paragraph> = emptyList())

	@Serializable
	data class Paragraph(
		@ProtoNumber(2) val lines: List<Line> = emptyList(),
		@ProtoNumber(3) val geometry: Geometry? = null,
		@ProtoNumber(5) val contentLanguage: String = "",
	)

	@Serializable
	data class Line(
		@ProtoNumber(1) val words: List<Word> = emptyList(),
		@ProtoNumber(2) val geometry: Geometry? = null,
	)

	@Serializable
	data class Word(
		@ProtoNumber(2) val plainText: String = "",
		@ProtoNumber(3) val textSeparator: String = "",
		@ProtoNumber(4) val geometry: Geometry? = null,
	)

	@Serializable
	data class Geometry(@ProtoNumber(1) val boundingBox: CenterRotatedBox? = null)

	@Serializable
	data class CenterRotatedBox(
		@ProtoNumber(1) val centerX: Float = 0f,
		@ProtoNumber(2) val centerY: Float = 0f,
		@ProtoNumber(3) val width: Float = 0f,
		@ProtoNumber(4) val height: Float = 0f,
		@ProtoNumber(6) val coordinateType: Int = 0,
	)

	const val PLATFORM_WEB = 3
	const val SURFACE_CHROMIUM = 4
	const val FILTER_AUTO = 7
	const val COORD_NORMALIZED = 1

	fun encodeRequest(jpeg: ByteArray, width: Int, height: Int, language: String): ByteArray {
		val request = ServerRequest(
			objectsRequest = ObjectsRequest(
				requestContext = RequestContext(
					requestId = RequestId(
						uuid = System.currentTimeMillis() * 1_000_000L + (0..999_999).random(),
						sequenceId = 1,
						imageSequenceId = 1,
					),
					clientContext = ClientContext(
						platform = PLATFORM_WEB,
						surface = SURFACE_CHROMIUM,
						localeContext = LocaleContext(language = language, region = "US", timeZone = "America/New_York"),
						clientFilters = AppliedFilters(listOf(AppliedFilter(FILTER_AUTO))),
					),
				),
				imageData = ImageData(
					payload = ImagePayload(jpeg),
					imageMetadata = ImageMetadata(width, height),
				),
			),
		)
		return ProtoBuf.encodeToByteArray(ServerRequest.serializer(), request)
	}

	fun decodeResponse(bytes: ByteArray): ServerResponse =
		ProtoBuf.decodeFromByteArray(ServerResponse.serializer(), bytes)
}
