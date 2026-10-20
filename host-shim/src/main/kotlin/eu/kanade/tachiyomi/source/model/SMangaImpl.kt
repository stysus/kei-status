package eu.kanade.tachiyomi.source.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class SMangaImpl : SManga {

    override var url: String = ""
    override var title: String = ""
    override var thumbnail_url: String? = null
    override var artist: String? = null
    override var author: String? = null
    override var status: Int = SManga.UNKNOWN
    override var description: String? = null
    override var genre: String? = null
    override var update_strategy: UpdateStrategy = UpdateStrategy.ALWAYS_UPDATE
    override var memo: JsonObject = buildJsonObject { }
    override var initialized: Boolean = false

    override fun toString(): String = "SMangaImpl(url=$url, title=$title)"
}
