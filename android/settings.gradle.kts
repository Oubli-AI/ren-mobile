pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // LiveKit (under the ElevenLabs SDK) needs audioswitch, published only on JitPack.
        exclusiveContent {
            forRepository { maven("https://jitpack.io") }
            filter { includeGroup("com.github.davidliu") }
        }
        // Mozilla Android Components (site permissions and the public suffix list) are published on Mozilla's Maven.
        exclusiveContent {
            forRepository { maven("https://maven.mozilla.org/maven2") }
            filter { includeGroup("org.mozilla.components") }
        }
    }
}
rootProject.name = "ren"
include(":app")
