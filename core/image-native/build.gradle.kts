plugins { id("com.android.library") }

android {
    namespace = "app.lifeos.core.image.nativebackend"
    compileSdk = 37
    ndkVersion = "28.2.13676358"
    defaultConfig {
        minSdk = 26
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++20", "-O3")
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation(project(":core:image"))
    testImplementation(kotlin("test-junit"))
}
