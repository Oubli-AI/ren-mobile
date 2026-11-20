import java.util.Base64
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// The workspace VM's host, where Caddy serves ren's backend, the hub and the agents (deploy/vm's REN_HOST):
// `workspaceHost` in ren.local.properties, or REN_WORKSPACE_HOST, or else the owner's domain.
val workspaceHost: String = Properties().apply {
    rootProject.file("ren.local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}.getProperty("workspaceHost") ?: System.getenv("REN_WORKSPACE_HOST")?.takeIf { it.isNotBlank() } ?: "rivet.ron-ai.dev"

android {
    namespace = "com.hiron.ren"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.hiron.ren"
        minSdk = 29
        targetSdk = 36
        // Phones only: the ElevenLabs SDK's WebRTC library is ~12 MB per ABI, and the x86/armv7 copies were 34 MB.
        ndk { abiFilters += "arm64-v8a" }
        versionCode = 16
        versionName = "0.5.3"
        // Ready-to-use defaults from ren.local.properties (never committed), or else from the build machine's
        // environment (REN_ANSWERING_NUMBER and the rest, a cloud environment's secrets); they stay editable in Settings.
        val local = Properties().apply {
            rootProject.file("ren.local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
        }
        fun baked(key: String, variable: String): String {
            val value = local.getProperty(key) ?: System.getenv(variable).orEmpty()
            return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }
        buildConfigField("String", "DEFAULT_ANSWERING_NUMBER", baked("answeringNumber", "REN_ANSWERING_NUMBER"))
        buildConfigField("String", "DEFAULT_BRAVE_SEARCH_KEY", baked("braveSearchKey", "REN_BRAVE_SEARCH_KEY"))
        buildConfigField("String", "DEFAULT_BRAVE_ANSWERS_KEY", baked("braveAnswersKey", "REN_BRAVE_ANSWERS_KEY"))
        buildConfigField("String", "DEFAULT_GEMINI_TOKEN", baked("geminiToken", "REN_GEMINI_TOKEN"))
        buildConfigField("String", "WORKSPACE_HOST", "\"$workspaceHost\"")
        // The agents' seven Brave search tools (client tools and on the hub), off unless `braveSearchTools=true`.
        buildConfigField("boolean", "BRAVE_SEARCH_TOOLS", (local.getProperty("braveSearchTools") == "true").toString())
    }

    signingConfigs {
        getByName("debug") {
            // The owner's debug keystore, base64 in REN_DEBUG_KEYSTORE, so a build on another machine (a cloud
            // session) signs with the key the installed app has and installs over it. Its password and alias are
            // the debug defaults.
            System.getenv("REN_DEBUG_KEYSTORE")?.takeIf { it.isNotBlank() }?.let { encoded ->
                val keystore = layout.buildDirectory.file("debug.keystore").get().asFile
                keystore.parentFile.mkdirs()
                keystore.writeBytes(Base64.getMimeDecoder().decode(encoded))
                storeFile = keystore
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Sideload builds are signed with the owner's debug key; a store build would use its own key.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    // The APK is sideloaded, so nothing compresses it on the way: the WebRTC library and the dex files go in compressed.
    packaging {
        jniLibs { useLegacyPackaging = true }
        dex { useLegacyPackaging = true }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Real rendering for the screen tests' screenshots.
            it.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
            it.systemProperty("ren.screenshots", rootProject.layout.buildDirectory.dir("screens").get().asFile.path)
        }
    }
    lint {
        abortOnError = true
        warningsAsErrors = false
    }
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
        extraWarnings.set(true)
        progressiveMode.set(true)
        freeCompilerArgs.addAll(
            "-Xjsr305=strict",
            "-Xjspecify-annotations=strict",
            "-Xreturn-value-checker=full",
            "-Xconsistent-data-class-copy-visibility",
        )
    }
}

// Ren's bundled pages (android/web: the robot, and ren desktop's search page) are built by Vite into the app's assets,
// so an APK always carries the pages its sources describe. Needs Node and npm on the build machine.
val web = rootProject.layout.projectDirectory.dir("web")
val webInstall = tasks.register<Exec>("webInstall") {
    workingDir = web.asFile
    commandLine("npm", "ci")
    inputs.file(web.file("package-lock.json"))
    outputs.file(web.file("node_modules/.package-lock.json"))
}
val webBuild = tasks.register<Exec>("webBuild") {
    dependsOn(webInstall)
    workingDir = web.asFile
    commandLine("npm", "run", "build")
    // The pages call ren's backend on the workspace host; Vite lets the environment override web/.env.
    environment("VITE_API_BASE_URL", "https://$workspaceHost")
    inputs.property("workspaceHost", workspaceHost)
    inputs.dir(web.dir("src"))
    inputs.dir(web.dir("scripts"))
    inputs.files(
        web.file("package-lock.json"), web.file("vite.config.ts"), web.file("tailwind.config.ts"), web.file(".env"),
        web.file("tsconfig.json"), web.file("tsconfig.app.json"), web.file("tsconfig.cato.json"), web.file("tsconfig.ren.json"),
    )
    outputs.dir(layout.projectDirectory.dir("src/main/assets/ren/web"))
}
tasks.named("preBuild") { dependsOn(webBuild) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.elevenlabs.android)
    implementation(libs.mcp.server)
    implementation(libs.mcp.client)
    implementation(libs.ktor.client.cio)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.browser)
    implementation(libs.okhttp.dnsoverhttps)
    implementation(libs.mozilla.publicsuffixlist)
    implementation(libs.mozilla.sitepermissions)
    implementation(libs.mozilla.concept.engine)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.ktor.server.cio)
    testImplementation(libs.ktor.server.websockets)
    testImplementation(libs.org.json)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
}
