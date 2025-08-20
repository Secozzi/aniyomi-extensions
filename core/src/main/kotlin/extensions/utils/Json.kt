@file:Suppress("unused")

package extensions.utils

// From https://github.com/keiyoushi/extensions-source/blob/main/core/src/main/kotlin/keiyoushi/utils/Json.kt

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Parses JSON string into an object of type [T].
 */
context(source: Source)
inline fun <reified T> String.parseAs(json: Json = source.json): T =
    json.decodeFromString(this)

/**
 * Parses the response body into an object of type [T].
 */
context(source: Source)
inline fun <reified T> Response.parseAs(json: Json = source.json): T =
    use { json.decodeFromStream(body.byteStream()) }

/**
 * Serializes the object to a JSON string.
 */
context(source: Source)
inline fun <reified T> T.toJsonString(json: Json = source.json): String =
    json.encodeToString(this)

/**
 * Converts a string into a JSON request body.
 */
fun String.toJsonBody(): RequestBody =
    this.toRequestBody("application/json; charset=utf-8".toMediaType())

/**
 * Converts the object to a JSON request body.
 */
context(source: Source)
inline fun <reified T> T.toRequestBody(): RequestBody =
    this.toJsonString().toJsonBody()
