plugins {
    kotlin("android")
    id("kotlinx-serialization")
}

kotlin {
    jvmToolchain(17)

    compilerOptions {
        freeCompilerArgs.addAll(
            "-Xcontext-parameters",
            "-Xmulti-dollar-interpolation",
            "-Xjvm-default=all-compatibility",
            "-opt-in=kotlinx.serialization.ExperimentalSerializationApi",
        )
    }
}
