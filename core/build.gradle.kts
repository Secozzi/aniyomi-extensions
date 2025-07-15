plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    compileSdk = AndroidConfig.compileSdk
    namespace = AndroidConfig.coreNamespace

    defaultConfig {
        minSdk = AndroidConfig.minSdk
    }

    buildFeatures {
        resValues = false
        shaders = false
    }

    kotlinOptions {
        freeCompilerArgs += "-opt-in=kotlinx.serialization.ExperimentalSerializationApi"
    }

    // sourceSets {
    //     named("main") {
    //         manifest.srcFile("AndroidManifest.xml")
    //         res.setSrcDirs(listOf("res"))
    //     }
    // }
}

dependencies {
    compileOnly(versionCatalogs.named("libs").findBundle("common").get())
}
