import io.github.secozzi.gradle.api.ContentWarning

plugins {
    alias(proj.plugins.extension)
}

extension {
    name = "Torbox"
    versionCode = 1
    versionId = 2
    contentWarning = ContentWarning.SAFE
}
