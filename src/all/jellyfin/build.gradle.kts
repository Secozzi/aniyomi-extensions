import io.github.secozzi.gradle.api.ContentWarning

plugins {
    alias(proj.plugins.extension)
}

extension {
    name = "Jellyfin"
    qname = "JellyfinFactory"
    versionCode = 2
    versionId = 3
    contentWarning = ContentWarning.SAFE
    sourceNames = listOf("Jellyfin (1)", "Jellyfin (2)", "Jellyfin (3)")
}

dependencies {
    implementation("org.apache.commons:commons-text:1.11.0")
}
