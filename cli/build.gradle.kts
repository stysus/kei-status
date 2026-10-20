plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

dependencies {
    implementation(project(":engine"))
    testImplementation(libs.junit)
}

application {
    mainClass.set("kei.status.cli.MainKt")
    // zstd-kmp loads a native library through java.lang.System::load.
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

// `./gradlew :cli:run` otherwise executes from cli/, which breaks the default --source-root.
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}
