package eu.kanade.tachiyomi.source.model

import kotlinx.serialization.json.JsonObject

@Suppress("UNUSED", "PropertyName")
interface SChapter {

    var url: String
    var name: String
    var chapter_number: Float
    var scanlator: String?
    var date_upload: Long
    var memo: JsonObject

    companion object {
        @JvmStatic
        fun create(): SChapter = SChapterImpl()
    }
}
