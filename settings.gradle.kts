loadAllIndividualExtensions()

/**
 * ===================================== COMMON CONFIGURATION ======================================
 */
include(":core")

/**
 * ======================================== HELPER FUNCTION ========================================
 */
fun loadAllIndividualExtensions() {
    File(rootDir, "src").eachDir { dir ->
        dir.eachDir { subdir ->
            include("src:${dir.name}:${subdir.name}")
        }
    }
}

fun File.eachDir(block: (File) -> Unit) {
    val files = listFiles() ?: return
    for (file in files) {
        if (file.isDirectory && file.name != ".gradle" && file.name != "build" && file.name != ".kotlin") {
            block(file)
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version("0.10.0")
}
