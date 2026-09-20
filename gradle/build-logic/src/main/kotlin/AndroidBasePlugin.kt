import com.android.build.api.dsl.ApplicationDefaultConfig
import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.DefaultConfig
import com.android.build.api.dsl.LibraryDefaultConfig
import io.github.secozzi.gradle.internal.configurations.configureKotlin
import io.github.secozzi.gradle.internal.extensions.proj
import io.github.secozzi.gradle.internal.extensions.spotlessTaskName
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

@Suppress("UNUSED")
class AndroidBasePlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        configureKotlin()

        android {
            compileSdk = proj.versions.android.sdk.compile.get().toInt()

            defaultConfig {
                minSdk = proj.versions.android.sdk.min.get().toInt()
                if (this is ApplicationDefaultConfig) {
                    targetSdk = proj.versions.android.sdk.target.get().toInt()
                }

                val proguardFile = file("proguard-rules.pro")
                if (proguardFile.exists()) {
                    when (this) {
                        is ApplicationDefaultConfig -> proguardFile(proguardFile)
                        is LibraryDefaultConfig -> consumerProguardFiles(proguardFile)
                    }
                }
            }
        }

        tasks.getByName("preBuild").dependsOn(spotlessTaskName())
    }
}

private fun Project.android(block: CommonExtension.() -> Unit) {
    extensions.configure(block)
}

private fun CommonExtension.defaultConfig(block: DefaultConfig.() -> Unit) {
    defaultConfig.apply(block)
}
