package io.github.secozzi.gradle.api.dsl

import io.github.secozzi.gradle.api.ContentWarning
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

abstract class AnimeExtension {
    abstract val name: Property<String>
    abstract val qname: Property<String>
    abstract val versionCode: Property<Int>
    abstract val versionId: Property<Int>
    abstract val contentWarning: Property<ContentWarning>
    abstract val torrent: Property<Boolean>
    abstract val sourceNames: ListProperty<String>
}
