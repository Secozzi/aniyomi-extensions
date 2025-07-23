plugins {
    id("extensions.android.library")
    id("extensions.kotlin")
    id("extensions.lint")
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
