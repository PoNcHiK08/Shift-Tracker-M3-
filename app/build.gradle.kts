plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
    alias(libs.plugins.google.services)
}

// Release signing policy (see README, "Signing"):
//  1. SIGNING_KEYSTORE_PATH (+ SIGNING_STORE_PASSWORD, SIGNING_KEY_ALIAS, SIGNING_KEY_PASSWORD) → that key.
//  2. Otherwise the public dev key from the repo, but only on an explicit opt-in
//     (env ALLOW_DEV_RELEASE_SIGNING=true or -PallowDevReleaseSigning=true), and with a warning in the log.
//  3. Neither → the release build stops before signing and says what to do.
// Debug builds always use the dev key.
val releaseKeystorePath: String? = System.getenv("SIGNING_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
val missingReleaseSigningVars: List<String> =
    if (releaseKeystorePath == null) emptyList()
    else listOf("SIGNING_STORE_PASSWORD", "SIGNING_KEY_ALIAS", "SIGNING_KEY_PASSWORD").filter { System.getenv(it).isNullOrEmpty() }
val allowDevReleaseSigning: Boolean =
    System.getenv("ALLOW_DEV_RELEASE_SIGNING").equals("true", ignoreCase = true) ||
        providers.gradleProperty("allowDevReleaseSigning").orNull.equals("true", ignoreCase = true)

android {
    namespace = "com.vkurylo.shifttracker"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.vkurylo.shifttracker"
        minSdk = 26
        targetSdk = 37
        // CI passes the run number so every build installs over the previous one.
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = "1.0.${System.getenv("VERSION_CODE") ?: "0"}"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Development key that lives in the repo (its password is public), so every GitHub build
        // has the same signature and installs as an update over the previous one. It is not a
        // secret: anyone can sign an APK with it. For a store release, use the SIGNING_* env vars.
        create("dev") {
            storeFile = file("signing/dev.keystore")
            storePassword = "shifttracker"
            keyAlias = "shifttracker"
            keyPassword = "shifttracker"
        }
        create("release") {
            if (releaseKeystorePath != null) {
                storeFile = file(releaseKeystorePath)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            } else {
                // Only used if checkReleaseSigning (below) lets the build through: explicit opt-in.
                initWith(getByName("dev"))
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("dev")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        generateLocaleConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            // Optional mirror for the Android runtime jars Robolectric downloads.
            System.getenv("ROBOLECTRIC_REPO_URL")?.let { test.systemProperty("robolectric.dependency.repo.url", it) }
            // Robolectric's Android 16 (SDK 36) runtime needs this on JDK 21.
            test.jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
        }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

// Cloud sync is optional. app/google-services.json (Firebase) is never committed: CI writes it from the
// GOOGLE_SERVICES_JSON secret, a local build reads your own copy. Without it the app builds and works as
// before, and Settings says cloud sync isn't available in this build.
googleServices {
    missingGoogleServicesStrategy = com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy.WARN
}

// Runs whenever a release is about to be signed (APK or bundle), never for debug builds or tests.
// Runs on every such build (no outputs), so the warning shows even when the configuration is cached.
val checkReleaseSigning = tasks.register("checkReleaseSigning") {
    group = "verification"
    description = "Stops a release build that has neither a release key nor an explicit dev-key opt-in."
    val keystore = releaseKeystorePath
    val missing = missingReleaseSigningVars
    val allowDev = allowDevReleaseSigning
    doLast {
        when {
            keystore != null && missing.isNotEmpty() -> throw GradleException(
                "Release signing: SIGNING_KEYSTORE_PATH is set, but ${missing.joinToString()} " +
                    (if (missing.size == 1) "is" else "are") + " missing.",
            )
            keystore != null -> logger.lifecycle("Release signing: using the key from SIGNING_KEYSTORE_PATH.")
            allowDev -> logger.warn(
                """
                |
                |============================================================================
                | WARNING: this release APK is signed with the PUBLIC DEVELOPMENT KEY
                | (app/signing/dev.keystore). Its password is in the repo, so anyone can sign
                | an APK with it. Fine for the GitHub test builds, NOT for a store release.
                | Explicitly allowed by ALLOW_DEV_RELEASE_SIGNING / -PallowDevReleaseSigning.
                |============================================================================
                |
                """.trimMargin(),
            )
            else -> throw GradleException(
                """
                |Release signing is not configured.
                |  - To sign with your own key, set SIGNING_KEYSTORE_PATH, SIGNING_STORE_PASSWORD,
                |    SIGNING_KEY_ALIAS and SIGNING_KEY_PASSWORD.
                |  - To sign with the public development key (GitHub test builds only), opt in
                |    explicitly: ALLOW_DEV_RELEASE_SIGNING=true ./gradlew assembleRelease
                |    or ./gradlew assembleRelease -PallowDevReleaseSigning=true
                """.trimMargin(),
            )
        }
    }
}
tasks.matching { it.name == "validateSigningRelease" }.configureEach { dependsOn(checkReleaseSigning) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.graphics.shapes)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)

    implementation(libs.androidx.datastore.preferences)
    // Home-screen widget.
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    // Glance itself only asks for WorkManager 2.7.1 (2021), older than Android 14-16's job rules; use a current one.
    implementation(libs.androidx.work.runtime)

    // Optional cloud sync: Firebase Auth + Firestore, Google sign-in through Credential Manager.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
    implementation(libs.kotlinx.coroutines.play.services)

    testImplementation(libs.junit)
    testImplementation(libs.androidx.work.testing)
    // UI smoke test on the JVM (Robolectric) — exercises the real Room/Hilt/Compose stack.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.hilt.android.testing)
    kspTest(libs.hilt.compiler)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
