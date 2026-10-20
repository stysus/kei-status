package eu.kanade.tachiyomi.source.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

class SChapterImpl : SChapter {

    override var url: String = ""
    override var name: String = ""
    override var chapter_number: Float = -1f
    override var scanlator: String? = null
    override var date_upload: Long = 0L
    override var memo: JsonObject = buildJsonObject { }

    override fun toString(): String = "SChapterImpl(url=$url, name=$name)"
}
