plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":android-shim"))
    testImplementation(libs.junit)
}
