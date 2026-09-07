plugins { id("org.jetbrains.kotlin.jvm") }
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:language"))
    implementation(project(":core:image"))
    testImplementation(kotlin("test"))
}
tasks.test { useJUnitPlatform() }
