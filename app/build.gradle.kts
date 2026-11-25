import groovy.json.JsonSlurper
import java.net.URI

plugins {
    alias(libs.plugins.ksp)
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val workspaceApiBaseUrl = providers.gradleProperty("WORKSPACE_API_BASE_URL").orElse("").get().trim().trimEnd('/')
if (workspaceApiBaseUrl.isNotEmpty()) {
    val uri = URI(workspaceApiBaseUrl)
    require(uri.scheme == "https" && uri.host != null && uri.rawUserInfo == null &&
        uri.rawQuery == null && uri.rawFragment == null && uri.path.isEmpty()) {
        "WORKSPACE_API_BASE_URL must be an HTTPS origin, e.g. https://todolist-api.onrender.com"
    }
}

android {
    namespace = "com.finalexam.to_do_list"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.finalexam.to_do_list"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "WORKSPACE_API_BASE_URL", "\"$workspaceApiBaseUrl\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        viewBinding = true
        resValues = true
        buildConfig = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.maxHeapSize = "1536m"
            it.maxParallelForks = 1
            it.systemProperty("dbml.path", project.file("src/test/fixtures/TodoList_DB_v2.dbml").absolutePath)
            it.inputs.file(project.file("src/test/fixtures/TodoList_DB_v2.dbml"))
        }
    }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

// A checkout can build before Firebase is configured. Sign-in requires valid Firebase configuration.
val firebaseConfig = file("google-services.json")
if (firebaseConfig.exists()) {
    val config = JsonSlurper().parse(firebaseConfig) as Map<*, *>
    val client = (config["client"] as List<*>).map { it as Map<*, *> }.firstOrNull {
        val info = it["client_info"] as Map<*, *>
        (info["android_client_info"] as Map<*, *>)["package_name"] == "com.finalexam.to_do_list"
    } ?: error("google-services.json must contain com.finalexam.to_do_list")
    val project = config["project_info"] as Map<*, *>
    android.defaultConfig {
        resValue("string", "google_app_id", (client["client_info"] as Map<*, *>)["mobilesdk_app_id"].toString())
        resValue("string", "google_api_key", ((client["api_key"] as List<*>).first() as Map<*, *>)["current_key"].toString())
        resValue("string", "project_id", project["project_id"].toString())
        resValue("string", "gcm_defaultSenderId", project["project_number"].toString())
    }
}

dependencies {
    constraints {
        implementation("androidx.fragment:fragment:1.8.9") {
            because("Transitive Fragment 1.1.0 is incompatible with the Activity Result API used by registration.")
        }
    }
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation("androidx.room:room-runtime:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.test:core:1.7.0")
    implementation("com.google.firebase:firebase-auth:24.0.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.firebase:firebase-firestore:26.6.0")
    implementation("androidx.work:work-runtime-ktx:2.11.0")
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
