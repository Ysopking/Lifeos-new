plugins { id("org.jetbrains.kotlin.jvm") }
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":core:runtime"))
    implementation(project(":core:runtime-buildstudio"))
    implementation(project(":core:runtime-reasoning"))
    implementation(project(":core:runtime-web"))
    implementation(project(":core:runtime-deepsearch"))
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
tasks.test { useJUnitPlatform() }
