import java.util.Locale

plugins {
    id("java-library")
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

// Keep evaluation utilities in their own directory while bundling them with the CLI listener.
sourceSets.main { java.srcDir(rootProject.file("evaluate/utils")) }

// help.md 与命令实现放在一起维护，同时打进 core jar，供 `rwx-help` 从 classpath 读取。
tasks.processResources {
    from(rootProject.layout.projectDirectory.dir("evaluate/micro")) {
        include("*/commands.md")
        into("micro")
    }
    from(rootProject.layout.projectDirectory.dir("agent-operation")) {
        include("help.md")
        into("com/corrodinggames/rts/game/ai/custom/rwx_cli_tool")
    }
}

dependencies {
    api(project(":mod-api"))
    implementation(libs.compose.runtime)
    // The Compose desktop host supplies Skiko and its platform native library.
    // Keep AWT classes out of Android's transitive runtime dependencies.
    compileOnly(libs.skiko.awt)
    compileOnly(libs.httpclient)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.commons.compress)
    api(libs.kotlinx.coroutines.core)
    api(libs.tomlkt)
    api(libs.koin.core)
    api(libs.kotlinx.serialization.json)
    implementation(kotlin("reflect"))
    testImplementation(kotlin("test"))
    testImplementation(libs.skiko.awt)
    val skiaOs = System.getProperty("os.name").lowercase(Locale.ROOT).let {
        when {
            it.contains("mac") || it.contains("darwin") -> "macos"
            it.contains("win") -> "windows"
            else -> "linux"
        }
    }
    val skiaArch = System.getProperty("os.arch").lowercase(Locale.ROOT).let {
        if (it == "aarch64" || it == "arm64") "arm64" else "x64"
    }
    testRuntimeOnly("org.jetbrains.skiko:skiko-awt-runtime-$skiaOs-$skiaArch:${libs.versions.skikoVersion.get()}")
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.httpclient)
}

// CASE4 scene preview: renders the generated layout offline, without booting a match.
// `.\gradlew.bat :core:previewCase4 -Ptask=4_1_1 -Pseed=1,2,3,5,8 [-Pscale=9] [-Pout=agent-tmp/case4-preview]`
tasks.register<JavaExec>("previewCase4") {
    group = "verification"
    description = "Render CASE4 layouts to PNG/JSON for the given task and seeds"
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets.getByName("test").runtimeClasspath
    mainClass.set("com.corrodinggames.rts.game.ai.custom.micro.Case4ScenePreview")
    workingDir = rootProject.projectDir
    args(
        providers.gradleProperty("task").getOrElse("4_1_1"),
        providers.gradleProperty("seed").getOrElse("1,2,3,5,8"),
        providers.gradleProperty("out").getOrElse("agent-tmp/case4-preview"),
        providers.gradleProperty("scale").getOrElse("9"),
        providers.gradleProperty("ranges").getOrElse("0"),
    )
}
