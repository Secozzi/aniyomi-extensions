import io.github.secozzi.gradle.api.ContentWarning

plugins {
    alias(proj.plugins.extension)
}

extension {
    name = "Stremio"
    versionCode = 33
    contentWarning = ContentWarning.MIXED
    torrent = true
}

dependencies {
    implementation("org.apache.commons:commons-text:1.11.0")
}
