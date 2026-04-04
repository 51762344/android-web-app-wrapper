import java.util.Properties
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

plugins {
    id("com.android.application")
}

abstract class GenerateLauncherResourcesTask : DefaultTask() {
    @get:Input
    abstract val foregroundResource: Property<String>

    @get:Input
    abstract val backgroundResource: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val drawableDir = outputDir.dir("drawable").get().asFile.apply { mkdirs() }

        drawableDir.resolve("ic_launcher_foreground.xml").writeText(
            launcherInsetXml(foregroundResource.get()),
        )
        drawableDir.resolve("ic_launcher_background.xml").writeText(
            launcherInsetXml(backgroundResource.get()),
        )
    }

    private fun launcherInsetXml(drawableResource: String): String {
        return """
            |<?xml version="1.0" encoding="utf-8"?>
            |<inset xmlns:android="http://schemas.android.com/apk/res/android"
            |    android:drawable="@drawable/$drawableResource" />
        """.trimMargin()
    }
}

val appConfigPath = ((project.findProperty("appConfigFile") as String?) ?: "app-config.properties").trim()
val appConfigFile = rootProject.file(appConfigPath)
require(appConfigFile.exists()) {
    "App config file not found: ${appConfigFile.path}. Pass -PappConfigFile=<path-to-properties> if needed."
}

val appConfig = Properties().apply {
    appConfigFile.inputStream().use(::load)
}

fun readAppConfig(key: String, defaultValue: String? = null): String {
    val configuredValue = appConfig.getProperty(key)?.trim().orEmpty()
    return when {
        configuredValue.isNotEmpty() -> configuredValue
        defaultValue != null -> defaultValue
        else -> error("Missing required key '$key' in ${appConfigFile.path}")
    }
}

fun readBooleanAppConfig(key: String, defaultValue: Boolean): Boolean {
    return when (val rawValue = appConfig.getProperty(key)?.trim()?.lowercase()) {
        null, "" -> defaultValue
        "true" -> true
        "false" -> false
        else -> error("Invalid boolean value '$rawValue' for key '$key' in ${appConfigFile.path}")
    }
}

fun String.escapeForGradleString(): String = replace("\\", "\\\\").replace("\"", "\\\"")

val configuredAppName = readAppConfig("appName", "WebWrapper")
val configuredApplicationId = readAppConfig("applicationId", "com.example.webwrapper")
val configuredBaseUrl = readAppConfig("baseUrl")
val configuredLauncherForegroundResource = readAppConfig("launcherForegroundResource", "ic_foreground_default")
val configuredLauncherBackgroundResource = readAppConfig("launcherBackgroundResource", "ic_background_default")
val configuredEnablePullToRefresh = readBooleanAppConfig("enablePullToRefresh", true)

val generatedResDir = layout.buildDirectory.dir("generated/app-config-res/main/res").get().asFile
val generateLauncherResources = tasks.register<GenerateLauncherResourcesTask>("generateLauncherResources") {
    foregroundResource.set(configuredLauncherForegroundResource)
    backgroundResource.set(configuredLauncherBackgroundResource)
    outputDir.set(generatedResDir)
}

tasks.named("preBuild").configure {
    dependsOn(generateLauncherResources)
}

android {
    namespace = "com.example.webwrapper"
    compileSdk = 36

    sourceSets.getByName("main").res.directories.add(generatedResDir.path)

    defaultConfig {
        applicationId = configuredApplicationId
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        vectorDrawables.useSupportLibrary = true
        buildConfigField("String", "DEFAULT_BASE_URL", "\"${configuredBaseUrl.escapeForGradleString()}\"")
        buildConfigField("boolean", "ENABLE_PULL_TO_REFRESH", configuredEnablePullToRefresh.toString())
        manifestPlaceholders["appLabel"] = configuredAppName
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }

        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.core:core:1.18.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.webkit:webkit:1.15.0")
    implementation("com.google.android.material:material:1.13.0")
}
