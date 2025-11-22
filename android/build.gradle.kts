// app/build.gradle.kts — full, drop-in file

// Kotlin-DSL helpers from the protobuf Gradle plugin
import com.google.protobuf.gradle.*

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("com.google.protobuf") version "0.9.4"
}

android {
    namespace = "com.cryptostorm.xray"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.cryptostorm.xray"
        minSdk = 21
        targetSdk = 36
        versionCode = 2 // increment every release
        versionName = "1.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.3"
    }
    plugins {
        // Name "grpc" is referenced below in generateProtoTasks
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:1.63.0"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            // Generate lite Java classes for Android
            task.builtins {
                create("java") { option("lite") }
            }
            // Generate gRPC stubs (lite)
            task.plugins {
                create("grpc") { option("lite") }
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.androidx.compose.ui.graphics)

    // gRPC + Protobuf (lite)
    implementation("io.grpc:grpc-okhttp:1.63.0")
    implementation("io.grpc:grpc-protobuf-lite:1.63.0")
    implementation("io.grpc:grpc-stub:1.63.0")
    implementation("com.google.protobuf:protobuf-javalite:3.25.3")
    // (Optional) improves Android thread policy on older API levels
    implementation("io.grpc:grpc-android:1.63.0")
    // Needed because protoc-gen-grpc-java puts @javax.annotation.Generated on stubs
    // (annotation only; no runtime needed)
    compileOnly("javax.annotation:javax.annotation-api:1.3.2")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
