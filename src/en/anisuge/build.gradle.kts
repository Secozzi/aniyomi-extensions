import io.github.secozzi.gradle.api.ContentWarning

plugins {
    alias(proj.plugins.extension)
}

extension {
    name = "AniSuge"
    versionCode = 1
    contentWarning = ContentWarning.MIXED
}

dependencies {
    // Provided by the Aniyomi app at runtime
    compileOnly("org.jsoup:jsoup:1.18.3")
    // jsoup 1.18 annotates its API with jspecify; Kotlin needs it on the compile classpath
    compileOnly("org.jspecify:jspecify:1.0.0")
}
