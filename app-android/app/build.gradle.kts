import com.android.build.gradle.internal.cxx.configure.gradleLocalProperties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val localProps = gradleLocalProperties(rootDir, providers)
val blynkToken = localProps.getProperty("BLYNK_TOKEN", "")
val mqttHost = localProps.getProperty(
    "MQTT_HOST",
    ""
)
val mqttPort = localProps.getProperty("MQTT_PORT", "8883").toIntOrNull() ?: 8883
val mqttUsername = localProps.getProperty("MQTT_USERNAME", "")
val mqttPassword = localProps.getProperty("MQTT_PASSWORD", "")
val mqttTopic = localProps.getProperty("MQTT_TOPIC_COMMAND", "")

android {
    namespace = "com.iot.roomfreshener"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.iot.roomfreshener"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "BLYNK_TOKEN", "\"$blynkToken\"")
        buildConfigField("String", "MQTT_HOST", "\"$mqttHost\"")
        buildConfigField("int", "MQTT_PORT", mqttPort.toString())
        buildConfigField("String", "MQTT_USERNAME", "\"$mqttUsername\"")
        buildConfigField("String", "MQTT_PASSWORD", "\"$mqttPassword\"")
        buildConfigField("String", "MQTT_TOPIC_COMMAND", "\"$mqttTopic\"")
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
    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.fragment.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.core)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.org.eclipse.paho.client.mqttv3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation(libs.numberpicker)
}