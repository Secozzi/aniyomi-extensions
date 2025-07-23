import com.android.build.api.dsl.ApplicationDefaultConfig
import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

@Suppress("UnusedReceiverParameter")
fun Project.configureAndroid(commonExtension: CommonExtension<*, *, *, *, *, *>) {
    commonExtension.apply {
        compileSdk = AndroidConfig.COMPILE_SDK

        defaultConfig {
            if (this is ApplicationDefaultConfig) {
                targetSdk = AndroidConfig.TARGET_SDK
            }
            minSdk = AndroidConfig.MIN_SDK
        }

        buildFeatures {
            shaders = false
        }
    }
}

class VersionCatalogExt(project: Project) {
    private val libs: VersionCatalog = project
        .extensions
        .getByType<VersionCatalogsExtension>()
        .named("libs")

    fun version(key: String): String = libs.findVersion(key).get().requiredVersion
}
