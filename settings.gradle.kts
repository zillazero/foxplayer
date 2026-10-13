pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "FOX-Player"
include(":app")
include(":fox-sso-client")
project(":fox-sso-client").projectDir = file("../fox-sso-client")
