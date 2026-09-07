plugins { id("com.android.library") }

android {
    namespace = "app.lifeos.core.data"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
}

dependencies { implementation(project(":core:model")) }
