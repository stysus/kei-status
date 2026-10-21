package kei.status.engine

import android.app.Application
import eu.kanade.tachiyomi.network.NetworkHelper
import kei.status.platform.PlatformCrypto
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektRegistry
import uy.kohesive.injekt.api.addSingleton
import java.nio.file.Path

/**
 * Wires the host services extension code resolves through Injekt.
 *
 * `keiyoushi.utils.applicationContext` is `Injekt.get()`, so an Application must be registered
 * before the first source is constructed - including for sources that only touch preferences.
 * Themes also inject [Json] to parse embedded filter data, and `keiyoushi.utils.protoInstance`
 * injects [ProtoBuf] for the `parseAsProto` helpers.
 */
object HarnessBootstrap {

    /** Mirrors the app's JSON configuration: extension payloads gain fields over time. */
    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    /** Mirrors the app's protobuf configuration; extensions only decode with it. */
    val protoBuf: ProtoBuf = ProtoBuf

    @Volatile
    private var installed: Application? = null

    /**
     * Registers the host services once per JVM.
     *
     * A run over every extension executes thousands of modules in one process, and the registry is
     * global: installing again would build a fresh Application and OkHttp client per module and
     * leave the previous ones reachable. The first install wins, which is also what the app does.
     */
    fun install(scratchDir: Path): Application {
        installed?.let { return it }

        synchronized(this) {
            installed?.let { return it }

            val app = Application()
            app.attachRoot(scratchDir.toFile())

            // Before any source is constructed: extensions ask the JCE for transformations only
            // Android's BouncyCastle provides, and fail with their own decrypt errors without it.
            PlatformCrypto.install()

            val registry: InjektRegistry = Injekt
            registry.addSingleton(app)
            registry.addSingleton(NetworkHelper(app))
            registry.addSingleton(json)
            registry.addSingleton(protoBuf)

            installed = app
            return app
        }
    }
}
