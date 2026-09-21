plugins { id("com.android.library") }

android {
    namespace = "app.lifeos.core.data"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:language"))
    implementation(project(":core:field"))
    implementation(project(":core:runtime"))
    implementation(project(":core:runtime-reasoning"))
    implementation(project(":core:runtime-deepsearch"))
    implementation(project(":core:runtime-personal"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation(kotlin("test-junit"))
}