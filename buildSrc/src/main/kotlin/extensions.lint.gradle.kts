plugins {
    id("com.diffplug.spotless")
}

val libs = VersionCatalogExt(project)

spotless {
    kotlin {
        target("**/*.kt", "**/*.kts")
        targetExclude("**/build/**/*.kt")
        ktlint(libs.version("ktlint.core"))
            .editorConfigOverride(mapOf(
                "indent_size" to "4",
                "max_line_length" to "120",
                "ij_kotlin_allow_trailing_comma" to "true",
                "ij_kotlin_allow_trailing_comma_on_call_site" to "true",
                "ij_kotlin_name_count_to_use_star_import" to "2147483647",
                "ij_kotlin_name_count_to_use_star_import_for_members" to "2147483647",
                "ktlint_code_style" to "intellij_idea",
                "ktlint_standard_class-signature" to "disabled",
                "ktlint_standard_discouraged-comment-location" to "disabled",
                "ktlint_standard_function-expression-body" to "disabled",
                "ktlint_standard_function-signature" to "disabled",
            ))
        trimTrailingWhitespace()
        endWithNewline()
    }

    format("gradle") {
        target("**/*.gradle")
        trimTrailingWhitespace()
        endWithNewline()
    }

    format("xml") {
        target("**/*.xml")
        targetExclude("**/build/**/*.xml")
        trimTrailingWhitespace()
        endWithNewline()
    }
}
