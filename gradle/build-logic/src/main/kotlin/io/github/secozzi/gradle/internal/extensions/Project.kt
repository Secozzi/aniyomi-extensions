package io.github.secozzi.gradle.internal.extensions

import org.gradle.accessors.dm.LibrariesForLibs
import org.gradle.accessors.dm.LibrariesForProj
import org.gradle.api.Project
import org.gradle.kotlin.dsl.the

internal val Project.libs get() = the<LibrariesForLibs>()
internal val Project.proj get() = the<LibrariesForProj>()

internal fun Project.spotlessTaskName() = if (providers.environmentVariable("CI").orNull != "true") "spotlessApply" else "spotlessCheck"
