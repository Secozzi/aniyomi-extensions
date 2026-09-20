package extensions.utils

import android.app.Application
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import kotlinx.serialization.json.Json
import uy.kohesive.injekt.injectLazy

abstract class Source :
    AnimeHttpSource(),
    ConfigurableAnimeSource {
    override val supportsRelatedAnime = false

    protected val context: Application by injectLazy()

    protected open val migration: SharedPreferences.() -> Unit = {}

    open val json: Json by injectLazy()

    val preferences: SharedPreferences by lazy {
        context.getSharedPreferences("source_$id", 0x0000).also(migration)
    }

    protected val handler by lazy { Handler(Looper.getMainLooper()) }

    protected fun displayToast(message: String, length: Int = Toast.LENGTH_SHORT) {
        handler.post {
            Toast.makeText(context, message, length).show()
        }
    }
}
