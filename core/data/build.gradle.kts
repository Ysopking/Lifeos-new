plugins { id("com.android.library") }

android {
    namespace = "app.lifeos.core.data"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:field"))
    implementation(project(":core:runtime"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation(kotlin("test-junit"))
}