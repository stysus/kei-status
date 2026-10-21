plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(libs.kotlin.stdlib)
    api(libs.coroutines.core)
    api(libs.serialization.json)
    api(libs.serialization.json.okio)
    api(libs.serialization.protobuf)
    api(libs.org.json)
    api(libs.okhttp)
    api(libs.okhttp.brotli)
    api(libs.okhttp.zstd)
    api(libs.zstd.kmp.okio)
    api(libs.jsoup)
    api(libs.injekt.core)
    api(libs.webp.imageio)
    api(libs.bouncycastle)

    testImplementation(libs.junit)
}
