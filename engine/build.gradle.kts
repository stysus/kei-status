plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":host-shim"))
    implementation(libs.serialization.protobuf)
    testImplementation(libs.junit)
}
