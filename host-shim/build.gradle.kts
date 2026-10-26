plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":android-shim"))
    api(libs.injekt.core)
    testImplementation(libs.junit)
}
