package io.github.secozzi.gradle.internal

import kotlinx.serialization.Serializable

@Serializable
internal data class ResolvedSource(val name: String, val lang: String, val id: Long)

@Serializable
internal data class ExtensionMetadata(
    val module: String,
    val packageName: String,
    val name: String,
    val versionCode: Int,
    val versionName: String,
    val extensionLib: String,
    val contentWarning: Int,
    val isTorrent: Boolean,
    val sources: List<SourceMetadata>,
)

@Serializable
internal data class SourceMetadata(
    val id: Long,
    val name: String,
    val lang: String,
    val baseUrl: String,
    val mirrorUrls: List<String> = emptyList(),
)
