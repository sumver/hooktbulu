plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.sumver.tbulu"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sumver.tbulu"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.1"

//        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {

//    implementation(libs.appcompat)
//    implementation(libs.material)
//    implementation(files("libs\\XposedBridgeAPI-89.jar"))
    compileOnly(files("libs\\XposedBridgeAPI-89.jar")) //仅编译时有效
//    testImplementation(libs.junit)
//    androidTestImplementation(libs.ext.junit)
//    androidTestImplementation(libs.espresso.core)
}