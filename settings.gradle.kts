pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "LIFEOS-Next"
include(":app", ":core:model", ":core:runtime", ":core:data", ":core:image", ":core:image-native", ":core:language", ":core:scene")
