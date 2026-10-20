package eu.kanade.tachiyomi.source.model

import android.net.Uri

@Suppress("UNUSED")
class Page(
    val index: Int,
    val url: String = "",
    var imageUrl: String? = null,
    var uri: Uri? = null,
) {
    override fun toString(): String = "Page(index=$index, url=$url, imageUrl=$imageUrl)"
}
