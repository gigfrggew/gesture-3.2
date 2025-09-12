import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

// Top-level kotlin block – NOT inside android {}
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
        languageVersion.set(KotlinVersion.KOTLIN_2_0)
        apiVersion.set(KotlinVersion.KOTLIN_2_0)
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

android {
    namespace = "com.example.myapplication1"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.myapplication1"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        multiDexEnabled = true
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // ✅ REMOVE this deprecated block
    // kotlinOptions {
    //     jvmTarget = "11"
    // }

    buildFeatures {
        compose = true
    }

    lint {
        baseline = file("lint-baseline.xml")
        abortOnError = false
    }

    androidResources {
        noCompress += ".task"
        noCompress += ".tflite"
    }
    
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE"
            excludes += "/META-INF/LICENSE.txt"
            excludes += "/META-INF/license.txt"
            excludes += "/META-INF/NOTICE"
            excludes += "/META-INF/NOTICE.txt"
            excludes += "/META-INF/notice.txt"
            excludes += "/META-INF/ASL2.0"
        }
    }
}

// Clean tasks
tasks.register("cleanBuildCache") {
    doLast {
        delete(fileTree("build/tmp") { include("**/*.class") })
        delete("build/tmp/kotlin-classes")
        delete("build/intermediates/dex")
        delete("build/intermediates/javac")
    }
}

tasks.named("clean") {
    dependsOn("cleanBuildCache")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.multidex)

    implementation(libs.androidx.constraintlayout)
    implementation(libs.material)

    implementation(libs.kotlin.logging)
    implementation(libs.slf4j.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.runtime)
    implementation(libs.androidx.runtime.livedata)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.savedstate.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.savedstate)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.foundation)
    implementation(libs.androidx.savedstate)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)

    implementation(libs.androidx.camera.core.v130)
    implementation(libs.androidx.camera.camera2.v130)
    implementation(libs.androidx.camera.lifecycle.v130)
    implementation(libs.androidx.camera.view.v130)

    implementation(libs.tasks.vision.v020230731)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // TensorFlow Lite runtime
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")
    implementation("org.tensorflow:tensorflow-lite-metadata:0.4.4")
}