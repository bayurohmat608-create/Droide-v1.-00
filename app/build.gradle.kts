import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val releaseKeystorePath = providers.gradleProperty("DROIDE_KEYSTORE_PATH").orNull ?: System.getenv("DROIDE_KEYSTORE_PATH")
val releaseKeystorePassword = providers.gradleProperty("DROIDE_KEYSTORE_PASSWORD").orNull ?: System.getenv("DROIDE_KEYSTORE_PASSWORD")
val releaseKeyAlias = providers.gradleProperty("DROIDE_KEY_ALIAS").orNull ?: System.getenv("DROIDE_KEY_ALIAS")
val releaseKeyPassword = providers.gradleProperty("DROIDE_KEY_PASSWORD").orNull ?: System.getenv("DROIDE_KEY_PASSWORD")
val hasReleaseSigning = listOf(releaseKeystorePath, releaseKeystorePassword, releaseKeyAlias, releaseKeyPassword).all { !it.isNullOrBlank() }



val githubOAuthClientId = (providers.gradleProperty("DROIDE_GITHUB_OAUTH_CLIENT_ID").orNull
    ?: System.getenv("DROIDE_GITHUB_OAUTH_CLIENT_ID")).orEmpty().trim()
require(githubOAuthClientId.isEmpty() || Regex("[A-Za-z0-9._-]{8,128}").matches(githubOAuthClientId)) {
    "DROIDE_GITHUB_OAUTH_CLIENT_ID has an invalid format"
}




val githubAccountClientId = (providers.gradleProperty("DROIDE_GITHUB_ACCOUNT_CLIENT_ID").orNull
    ?: System.getenv("DROIDE_GITHUB_ACCOUNT_CLIENT_ID")).orEmpty().trim()
require(githubAccountClientId.isEmpty() || Regex("[A-Za-z0-9._-]{8,128}").matches(githubAccountClientId)) {
    "DROIDE_GITHUB_ACCOUNT_CLIENT_ID has an invalid format"
}
val githubAppSlug = (providers.gradleProperty("DROIDE_GITHUB_APP_SLUG").orNull
    ?: System.getenv("DROIDE_GITHUB_APP_SLUG")).orEmpty().trim()
require(githubAppSlug.isEmpty() || Regex("[a-z0-9](?:[a-z0-9-]{0,98}[a-z0-9])?").matches(githubAppSlug)) {
    "DROIDE_GITHUB_APP_SLUG has an invalid format"
}
val githubAccountExchangeUrl = (providers.gradleProperty("DROIDE_GITHUB_ACCOUNT_EXCHANGE_URL").orNull
    ?: System.getenv("DROIDE_GITHUB_ACCOUNT_EXCHANGE_URL")).orEmpty().trim()
require(githubAccountExchangeUrl.isEmpty() || Regex("https://[^\\s?#]+(?:/[^\\s?#]*)?").matches(githubAccountExchangeUrl)) {
    "DROIDE_GITHUB_ACCOUNT_EXCHANGE_URL must be an HTTPS URL without query or fragment"
}




val defaultGithubAccountRedirectUri = "com.baystudio.droide.oauth://github/callback"
val githubAccountRedirectUri = (providers.gradleProperty("DROIDE_GITHUB_ACCOUNT_REDIRECT_URI").orNull
    ?: System.getenv("DROIDE_GITHUB_ACCOUNT_REDIRECT_URI")).orEmpty().trim().ifEmpty { defaultGithubAccountRedirectUri }
val githubAccountRedirect = runCatching { URI(githubAccountRedirectUri) }
    .getOrElse { e -> throw GradleException("DROIDE_GITHUB_ACCOUNT_REDIRECT_URI is not a valid URI", e) }
val githubAccountRedirectHasNoExtras = githubAccountRedirect.rawUserInfo == null &&
    githubAccountRedirect.rawQuery == null && githubAccountRedirect.rawFragment == null
val githubAccountRedirectIsNative = githubAccountRedirectUri == defaultGithubAccountRedirectUri
val githubAccountRedirectIsHttps = githubAccountRedirect.scheme.equals("https", ignoreCase = true) &&
    !githubAccountRedirect.host.isNullOrBlank() && githubAccountRedirect.port in listOf(-1, 443) &&
    !githubAccountRedirect.rawPath.isNullOrBlank() && githubAccountRedirect.rawPath.startsWith("/")
require(githubAccountRedirectHasNoExtras && (githubAccountRedirectIsNative || githubAccountRedirectIsHttps)) {
    "DROIDE_GITHUB_ACCOUNT_REDIRECT_URI must be the Droide native callback or a clean HTTPS App Link"
}
fun buildConfigString(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.baystudio.droide"
    ndkVersion = "28.2.13676358"
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.baystudio.droide"
        minSdk = 29
        targetSdk = 36
        versionCode = 29
        versionName = "1.00"
        testApplicationId = "com.baystudio.droide.test"
        testInstrumentationRunner = "com.baystudio.droide.PhysicalDeviceSmokeInstrumentation"
        testFunctionalTest = true
        buildConfigField("String", "GITHUB_OAUTH_CLIENT_ID", buildConfigString(githubOAuthClientId))
        buildConfigField("String", "GITHUB_ACCOUNT_CLIENT_ID", buildConfigString(githubAccountClientId))
        buildConfigField("String", "GITHUB_APP_SLUG", buildConfigString(githubAppSlug))
        buildConfigField("String", "GITHUB_ACCOUNT_EXCHANGE_URL", buildConfigString(githubAccountExchangeUrl))
        buildConfigField("String", "GITHUB_ACCOUNT_REDIRECT_URI", buildConfigString(githubAccountRedirectUri))
        manifestPlaceholders.putAll(mapOf<String, Any>(
            "githubAccountCallbackScheme" to (githubAccountRedirect.scheme ?: ""),
            "githubAccountCallbackHost" to (githubAccountRedirect.host ?: ""),
            "githubAccountCallbackPath" to (githubAccountRedirect.rawPath ?: ""),
            "githubAccountCallbackAutoVerify" to githubAccountRedirectIsHttps.toString(),
        ))
    }

    // release certification must exercise the release variant, not a debug surrogate.

    testBuildType = "release"

    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(requireNotNull(releaseKeystorePath))
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = false
    }

    packaging {
        jniLibs {
            
            useLegacyPackaging = true
            keepDebugSymbols += setOf("**/libdroide_proot.so", "**/libproot-loader.so", "**/libproot-loader32.so")
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencyLocking {
    lockAllConfigurations()
}

dependencies {
    implementation(files("libs/xz-1.12.jar")) 
    
    implementation(files("libs/terminal-emulator-v0.118.0-java.aar"))
    implementation(files("libs/terminal-view-v0.118.0.aar"))
    

    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")

    
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.2")

    


    implementation("io.github.rosemoe:editor:0.24.6")
    implementation("io.github.rosemoe:language-textmate:0.24.6")

    
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okio:okio-jvm:3.17.0")

    
    implementation("com.baystudio.compat:kadb-android-compat:2.1.4")
    implementation("com.baystudio.compat:kadb-mdns-android-compat:2.1.4")

    


    implementation("org.eclipse.jgit:org.eclipse.jgit:6.10.1.202505221210-r")
    implementation("androidx.documentfile:documentfile:1.1.0")

    testImplementation("junit:junit:4.13.2")

    

    implementation("org.jsoup:jsoup:1.18.1")
    
    implementation("io.coil-kt:coil-compose:2.7.0")
}
