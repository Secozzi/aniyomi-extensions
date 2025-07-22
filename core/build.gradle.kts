plugins {
    id("extensions.android.library")
    id("extensions.kotlin")
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
