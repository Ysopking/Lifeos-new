plugins { id("org.jetbrains.kotlin.jvm") }
kotlin { jvmToolchain(17) }
dependencies {
    api(project(":core:model"))
    api(project(":core:language"))
    api(project(":core:field"))
    testImplementation(kotlin("test"))
}
tasks.test { useJUnitPlatform() }
