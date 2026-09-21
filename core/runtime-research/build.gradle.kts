plugins { id("org.jetbrains.kotlin.jvm") }
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":core:runtime-reasoning"))
    implementation(project(":core:runtime-deepsearch"))
    testImplementation(kotlin("test"))
}
tasks.test { useJUnitPlatform() }
