import io.github.rwx.build.AssetListGenerationSupport
import java.util.*
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
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

val lwjglVersion = libs.versions.lwjglVersion.get()
val webrtcJavaVersion = libs.versions.webrtcJavaVersion.get()

val hostOsName = System.getProperty("os.name").lowercase(Locale.ROOT)
val hostOsArch = System.getProperty("os.arch").lowercase(Locale.ROOT)
val isHostArm64 = hostOsArch.contains("aarch64") || hostOsArch.contains("arm64")
val isHostWindows = hostOsName.contains("win")
val isHostMac = hostOsName.contains("mac") || hostOsName.contains("darwin")
val lwjglClassifier = when {
    isHostWindows -> "natives-windows"
    isHostMac && isHostArm64 -> "natives-macos-arm64"
    isHostMac -> "natives-macos"
    isHostArm64 -> "natives-linux-arm64"
    else -> "natives-linux"
}
val webrtcClassifier = when {
    isHostWindows -> "windows-x86_64"
    isHostMac && isHostArm64 -> "macos-aarch64"
    isHostMac -> "macos-x86_64"
    isHostArm64 -> "linux-aarch64"
    else -> "linux-x86_64"
}

val assetListGeneration = AssetListGenerationSupport.register(project)

dependencies {
    implementation(project(":mod-api"))
    implementation(project(":core"))
    implementation(project(":slick2d-lwjgl3"))
    implementation(project(":ui"))
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.httpclient)
    implementation("org.jetbrains.compose.ui:ui-desktop:${libs.versions.composeVersion.get()}")
    implementation(libs.webrtc.java)
    runtimeOnly("dev.onvoid.webrtc:webrtc-java:$webrtcJavaVersion:$webrtcClassifier")
    implementation(libs.lwjgl3.awt) {
        exclude(group = "org.lwjgl")
    }
    val lwjglModules = listOf("lwjgl", "lwjgl-glfw", "lwjgl-opengl", "lwjgl-openal", "lwjgl-jawt")
    lwjglModules.forEach { module ->
        implementation("org.lwjgl:$module:$lwjglVersion")
    }
    val lwjglNativeModules = lwjglModules - "lwjgl-jawt"
    lwjglNativeModules.forEach { module ->
        runtimeOnly("org.lwjgl:$module:$lwjglVersion:$lwjglClassifier")
    }
    implementation(libs.slf4j.api)
    implementation(libs.steamworks4j)
    implementation(libs.logback.classic)
    testImplementation(kotlin("test"))
    testRuntimeOnly(libs.junit.platform.launcher)
}

val appName: String = project.property("appName") as String
val desktopMainClass = "io.github.rwx.DesktopMain"
val packageVersion = project.version.toString().substringBefore('-').substringBefore('+')

// assets/ 随运行时发布：打进 uber jar（DesktopPlatformStorage.extractBundledAssets
// 可从 jar 解包），同时经 compose.desktop.application.fromFiles 进 app-image。
tasks.named<Copy>("processResources") {
    dependsOn(assetListGeneration.task)
    from(rootProject.layout.projectDirectory.dir("assets")) {
        into("assets")
        exclude(AssetListGenerationSupport.runtimeAssetExcludes)
    }
}

afterEvaluate {
    // IDE/Gradle 直接跑时 $ROOTDIR 占位符不会被替换，显式覆写避免落到字面 $ROOTDIR
    tasks.findByName("run")?.let { task ->
        (task as JavaExec).apply {
            workingDir = project.file("..")
            jvmArgs = jvmArgs.filterNot { it.contains("launch.dir") }
            systemProperty("launch.dir", project.file("..").absolutePath)
        }
    }
}

tasks.register("rwxCliRun") {
    group = "application"
    description = "Runs the desktop game with the RWX CLI command terminal enabled."
    dependsOn("run")
}

tasks.matching { it.name == "run" }.configureEach {
    (this as JavaExec).apply {
        if (gradle.startParameter.taskNames.any { it.substringAfterLast(':') == "rwxCliRun" }) {
            // Configure-time args are reset by the compose plugin, so append at execution time.
            doFirst { args("--rwx-cli") }
        }
    }
}

val generatedMacIcon = layout.buildDirectory.file("generated-icons/logo.icns")

val headlessDist = tasks.register<Sync>("headlessDist") {
    group = "distribution"
    description = "Assembles the Java 25 headless host/client and portable CLI tools. No display server is required."
    into(layout.buildDirectory.dir("headless"))
    from(tasks.named("jar")) { into("lib") }
    from(configurations.runtimeClasspath) {
        into("lib")
        // Different Maven groups may publish the same jar basename (Compose does).
        eachFile { name = file.parentFile.name + "-" + name }
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
    from(rootProject.file("agent-operation/server/rwx-headless")) { into("bin") }
    from(rootProject.file("agent-operation/server/host-service.py")) { into("agent-operation/server") }
    from(rootProject.file("agent-operation/server/rwx.service")) { into("agent-operation/server") }
    from(rootProject.file("agent-operation/tools/rwx-cli.py")) { into("agent-operation/tools") }
}

tasks.register<Tar>("headlessArchive") {
    group = "distribution"
    dependsOn(headlessDist)
    compression = Compression.GZIP
    archiveFileName.set("rwx-headless.tar.gz")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(headlessDist)
}
val generateMacOsIcns = tasks.register<Exec>("generateMacOsIcns") {
    group = "build setup"
    description = "Generates the macOS ICNS app icon with iconutil."
    val iconSet = layout.projectDirectory.dir("src/main/resources/icons/logo.iconset")
    inputs.dir(iconSet)
    outputs.file(generatedMacIcon)
    doFirst {
        generatedMacIcon.get().asFile.parentFile.mkdirs()
    }
    commandLine(
        "iconutil",
        "--convert", "icns",
        "--output", generatedMacIcon.get().asFile.absolutePath,
        iconSet.asFile.absolutePath,
    )
}

compose {
    desktop {
        application {
            mainClass = desktopMainClass
            jvmArgs.addAll(
                listOf(
                    "-Dfile.encoding=UTF-8",
                    "-Dorg.lwjgl.opengl.contextAPI=native",
                    "-Dorg.lwjgl.system.stackSize=512",
                    "--enable-native-access=ALL-UNNAMED",
                    "--sun-misc-unsafe-memory-access=allow",
                    "-Dlaunch.dir=\$ROOTDIR",
                ),
            )
            fromFiles(rootProject.layout.projectDirectory.dir("assets").asFile)
            nativeDistributions {
                packageName = appName
                vendor = "RWX"
                description = "Cross-platform real-time strategy game"
                modules(
                    "java.compiler",
                    "java.instrument",
                    "java.management",
                    "java.naming",
                    "java.net.http",
                    "java.security.jgss",
                    "java.sql",
                    "jdk.charsets",
                    "jdk.localedata",
                    "jdk.unsupported",
                    "jdk.zipfs",
                )
                if (isHostWindows) {
                    targetFormats(TargetFormat.AppImage, TargetFormat.Exe, TargetFormat.Msi)
                } else if (isHostMac) {
                    targetFormats(TargetFormat.AppImage, TargetFormat.Dmg)
                } else {
                    targetFormats(TargetFormat.AppImage, TargetFormat.Deb)
                }
                windows {
                    iconFile.set(layout.projectDirectory.file("src/main/resources/icons/logo.ico"))
                    console = false
                    shortcut = true
                    menu = true
                    upgradeUuid = "7f3a2b1c-4d5e-4f60-8a9b-0c1d2e3f4a5b"
                }
                linux {
                    iconFile.set(layout.projectDirectory.file("src/main/resources/icons/logo.png"))
                }
                macOS {
                    iconFile.set(generatedMacIcon)
                    bundleID = "io.github.rwx"
                }
            }
        }
    }
}

afterEvaluate {
    tasks.matching {
        it.name in listOf("packageDmg", "packageDeb", "packageExe", "packageMsi")
    }.configureEach {
        dependsOn("packageAppImage")
    }
    tasks.matching {
        it.name in listOf(
            "createDistributable",
            "createReleaseDistributable",
            "packageDistributionForCurrentOS",
            "packageReleaseDistributionForCurrentOS",
        )
    }.configureEach {
        if (isHostMac) {
            dependsOn(generateMacOsIcns)
        }
    }
    // UberJar 合并多方已签名 jar，必须剥离签名否则 java -jar 报 Invalid signature file digest。
    tasks.matching { it.name in listOf("packageUberJarForCurrentOS", "packageReleaseUberJarForCurrentOS") }
        .configureEach {
            (this as org.gradle.jvm.tasks.Jar).apply {
                exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
            }
        }
}
