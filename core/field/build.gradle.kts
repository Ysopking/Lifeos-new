plugins { id("org.jetbrains.kotlin.jvm") }
kotlin { jvmToolchain(17) }
dependencies {
    api(project(":core:model"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation(kotlin("test"))
}
tasks.test { useJUnitPlatform() }
