import org.jetbrains.kotlin.gradle.dsl.JvmTarget
plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.roccoquest.openworld"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.roccoquest.teamup.openworld"
        minSdk = 24
        targetSdk = 35
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()?.plus(100) ?: 1
        versionName = "0.1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }
    signingConfigs {
        create("games") {
            storeFile = rootProject.file("keystore/games.keystore")
            storePassword = "roccoquest"; keyAlias = "games"; keyPassword = "roccoquest"
        }
    }
    buildTypes { debug { signingConfig = signingConfigs.getByName("games") }; release { signingConfig = signingConfigs.getByName("games") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
dependencies { implementation(project(":teamup3d:engine")) }
