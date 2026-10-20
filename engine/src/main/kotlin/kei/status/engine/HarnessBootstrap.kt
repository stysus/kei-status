package kei.status.engine

import android.app.Application
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.serialization.json.Json
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektRegistry
import uy.kohesive.injekt.api.addSingleton
import java.nio.file.Path

/**
 * Wires the host services extension code resolves through Injekt.
 *
 * `keiyoushi.utils.applicationContext` is `Injekt.get()`, so an Application must be registered
 * before the first source is constructed - including for sources that only touch preferences.
 * Themes also inject [Json] to parse embedded filter data.
 */
object HarnessBootstrap {

    /** Mirrors the app's JSON configuration: extension payloads gain fields over time. */
    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    fun install(scratchDir: Path): Application {
        val app = Application()
        app.attachRoot(scratchDir.toFile())

        val registry: InjektRegistry = Injekt
        registry.addSingleton(app)
        registry.addSingleton(NetworkHelper(app))
        registry.addSingleton(json)

        return app
    }
}
