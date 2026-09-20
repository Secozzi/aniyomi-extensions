import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.gradle.tasks.PackageAndroidArtifact
import io.github.secozzi.gradle.api.dsl.AnimeExtension
import io.github.secozzi.gradle.internal.ExtensionMetadata
import io.github.secozzi.gradle.internal.ResolvedSource
import io.github.secozzi.gradle.internal.SourceMetadata
import io.github.secozzi.gradle.internal.extensions.alias
import io.github.secozzi.gradle.internal.extensions.compileOnly
import io.github.secozzi.gradle.internal.extensions.implementation
import io.github.secozzi.gradle.internal.extensions.libs
import io.github.secozzi.gradle.internal.extensions.plugins
import io.github.secozzi.gradle.internal.extensions.proj
import io.github.secozzi.gradle.tasks.GenerateManifestTask
import io.github.secozzi.gradle.tasks.GenerateSourceInfoTask
import kotlinx.serialization.json.Json
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.BasePluginExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType

@Suppress("UNUSED")
class ExtensionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        plugins {
            alias(libs.plugins.android.application)
            alias(libs.plugins.kotlin.serialization)

            alias(proj.plugins.android.base)
            alias(proj.plugins.spotless)
        }

        val extension = extensions.create<AnimeExtension>("extension")
        val dirSuffix = "${project.parent?.name}.${project.name}"
        val applicationIdSuffix = "${project.parent?.name}.${project.name}"

        android {
            namespace = "eu.kanade.tachiyomi.animeextension"

            defaultConfig {
                this.applicationIdSuffix = applicationIdSuffix
            }

            sourceSets {
                named("main") {
                    manifest.srcFile(rootProject.file("common/AndroidManifest.xml"))
                    java.directories.clear()
                    java.directories.add("src")
                    kotlin.directories.clear()
                    kotlin.directories.add("src")
                    res.directories.clear()
                    res.directories.add("res")
                    assets.directories.clear()
                    assets.directories.add("assets")
                }
            }

            lint {
                checkReleaseBuilds = false
            }

            signingConfigs {
                create("release") {
                    storeFile = rootProject.file("signingkey.jks")
                    storePassword = providers.environmentVariable("KEY_STORE_PASSWORD").orNull
                    keyAlias = providers.environmentVariable("ALIAS").orNull
                    keyPassword = providers.environmentVariable("KEY_PASSWORD").orNull
                }
            }

            buildTypes {
                named("release") {
                    signingConfig = if (rootProject.file("signingkey.jks").exists()) {
                        signingConfigs.getByName("release")
                    } else {
                        signingConfigs.getByName("debug")
                    }
                    isMinifyEnabled = true
                    proguardFiles(rootProject.file("common/proguard-rules.pro"))
                    @Suppress("UnstableApiUsage")
                    vcsInfo.include = false
                }
            }

            dependenciesInfo {
                includeInApk = false
            }

            buildFeatures {
                buildConfig = true
            }

            packaging {
                resources.excludes.add("kotlin-tooling-metadata.json")
            }
        }

        val versionCodeProvider = extension.versionCode
        val extensionLib = proj.versions.ext.lib.get()
        val versionNameProvider = versionCodeProvider.map { "$extensionLib.$it" }
        val torrentProvider = extension.torrent.orElse(false)

        val androidVersionCodeProvider = versionCodeProvider.map { versionCode ->
            extensionLib.toInt().times(1000) + versionCode
        }

        val manifestTask = tasks.register<GenerateManifestTask>("generateExtensionManifest") {
            this.extensionName.set(extension.name)
            this.contentWarning.set(extension.contentWarning)
            this.qualifiedName.set(extension.qname.orElse(extension.name))
            this.torrent.set(torrentProvider)
            this.extensionLib.set(extensionLib)
        }

        val sourceInfoTask = tasks.register<GenerateSourceInfoTask>("generateSourceInfo") {
            this.outputFile.set(layout.buildDirectory.file("source-info.json"))
        }

        androidComponents {
            onVariants { variant ->
                variant.sources.manifests.addStaticManifestFile("AndroidManifest.xml")
                variant.sources.manifests.addGeneratedManifestFile(manifestTask) { it.outputFile }

                variant.outputs.forEach { output ->
                    output.versionCode.set(androidVersionCodeProvider)
                    output.versionName.set(versionNameProvider)
                }
            }
        }

        base {
            archivesName.set(versionNameProvider.map { "aniyomi-$applicationIdSuffix-v$it" })
        }

        dependencies {
            implementation(project(":core"))
            compileOnly(libs.bundles.common)
        }

        afterEvaluate {
            val extName = extension.name.get()
            val sourceNames = extension.sourceNames.get().ifEmpty { listOf(extName) }

            val resolvedSources = sourceNames.map { source ->
                val lang = applicationIdSuffix.substringBefore('.')
                val id = computeSourceId(source, lang, extension.versionId.orElse(1).get())
                ResolvedSource(source, lang, id)
            }

            val packageName = "eu.kanade.tachiyomi.animeextension.$applicationIdSuffix"
            val sourceInfos = resolvedSources.map { source ->
                SourceMetadata(
                    id = source.id,
                    name = source.name,
                    lang = source.lang,
                    baseUrl = "",
                    mirrorUrls = emptyList(),
                )
            }
            val extensionInfo = ExtensionMetadata(
                module = dirSuffix,
                packageName = packageName,
                name = extName,
                versionCode = androidVersionCodeProvider.get(),
                versionName = "$extensionLib.${versionCodeProvider.get()}",
                extensionLib = extensionLib,
                // Proto secozzi.gradle.api.ContentWarning: UNSPECIFIED=0, SAFE=1, MIXED=2, NSFW=3 (enum ordinal + 1).
                contentWarning = extension.contentWarning.get().ordinal + 1,
                isTorrent = torrentProvider.get(),
                sources = sourceInfos,
            )
            val sourceInfoJson = Json.encodeToString(extensionInfo)
            sourceInfoTask.configure { content.set(sourceInfoJson) }
            tasks.named("assembleRelease").configure { dependsOn(sourceInfoTask) }

            tasks.withType<PackageAndroidArtifact>().configureEach {
                createdBy.set("")
                doFirst {
                    appMetadata.asFile.orNull?.writeText("")
                }
            }
        }
    }
}

private fun computeSourceId(name: String, lang: String, versionId: Int = 1): Long {
    val key = "${name.lowercase()}/$lang/$versionId"
    val bytes = java.security.MessageDigest.getInstance("MD5").digest(key.toByteArray())
    return (0..7).map { bytes[it].toLong() and 0xff }
        .reduce { acc, l -> (acc shl 8) or l } and Long.MAX_VALUE
}

private fun Project.android(block: ApplicationExtension.() -> Unit) {
    extensions.configure(block)
}

private fun Project.androidComponents(block: ApplicationAndroidComponentsExtension.() -> Unit) {
    extensions.configure(block)
}

private fun Project.base(block: BasePluginExtension.() -> Unit) {
    extensions.configure(block)
}
