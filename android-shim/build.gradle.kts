plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(libs.kotlin.stdlib)
    api(libs.coroutines.core)
    api(libs.serialization.json)
    api(libs.serialization.json.okio)
    api(libs.org.json)
    api(libs.okhttp)
    implementation(libs.okhttp.brotli)
    implementation(libs.okhttp.zstd)
    api(libs.zstd.kmp.okio)
    implementation(libs.jsoup)
    implementation(libs.webp.imageio)
    implementation(libs.avif.imageio)
    implementation(libs.bouncycastle)
    implementation(libs.quickjs.jvm) {
        // androidx.annotation is CLASS-retention; the JVM drops missing annotations, and the
        // artifact only resolves from Google's Maven, which this build does not use.
        exclude(group = "androidx.annotation")
    }

    testImplementation(libs.junit)
}
