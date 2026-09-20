pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "LIFEOS-Next"
include(
    ":app",
    ":core:model",
    ":core:field",
    ":core:runtime",
    ":core:runtime-contracts",
    ":core:runtime-personal",
    ":core:data",
    ":core:image",
    ":core:image-native",
    ":core:language",
    ":core:life",
    ":core:creative",
    ":core:scene",
    ":host:buildstudio",
)
