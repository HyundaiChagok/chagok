pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Pleos Playground SDK(Vehicle, TextToSpeech, NaviHelper). TextToSpeech가 security 그룹의 universal-access를 끌고 온다.
        maven("https://nexus-playground.pleos.ai/repository/maven-releases/") {
            content {
                includeGroup("ai.pleos.playground")
                includeGroup("ai.pleos.playground.security")
            }
        }
    }
}

rootProject.name = "chagok"

include(":core", ":pleos", ":app")
