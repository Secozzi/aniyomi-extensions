plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)

    alias(proj.plugins.android.base)
    alias(proj.plugins.spotless)
}

android {
    namespace = "extensions.core"

    buildFeatures {
        resValues = false
    }
}

dependencies {
    compileOnly(versionCatalogs.named("libs").findBundle("common").get())
}
