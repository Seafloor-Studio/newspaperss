import java.io.File
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kover)
}

val ciRun = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
val ciCommit = System.getenv("GITHUB_SHA")?.take(7)

// Each ABI's APK has version code base * 10 + this. A store offers a device the highest code it
// can run, so a 64-bit ABI comes after its 32-bit one; F-Droid's recipe uses the same numbers.
val ABI_CODES = mapOf("armeabi-v7a" to 1, "arm64-v8a" to 2, "x86" to 3, "x86_64" to 4)

// sherpa-onnx's libraries, Kokoro's 30-odd MB. Named here because two rules below depend on
// exactly this list: what the 32-bit APKs leave out, and what a debug 64-bit APK must have.
val KOKORO_LIBS = listOf("libonnxruntime.so", "libsherpa-onnx-c-api.so", "libsherpa-onnx-cxx-api.so", "libsherpa-onnx-jni.so")

// What counts as part of Kokoro in a built APK or bundle. A pattern rather than the list above,
// because a sherpa-onnx that added or renamed a library would walk straight past the list, which
// is the regression the checks exist to catch. The app's other native code (androidx.graphics.path,
// datastore_shared_counter) ships in every build and matches neither word.
val KOKORO_LIB_WORDS = listOf("sherpa", "onnx")

/** Kokoro needs a 64-bit phone. The others carry no part of it, in any build type. */
val ABI_64 = setOf("arm64-v8a", "x86_64")

android {
    namespace = "com.app.newspaperss"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.app.newspaperss"
        minSdk = 26
        targetSdk = 37
        versionCode = 2
        versionName = "0.1.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // One debug key for every machine, so a newer debug build from CI installs over an
        // older one instead of failing on a signature mismatch. Not a secret: debug-only.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            // Only configured when CI provides a keystore, so ordinary builds don't
            // fail validateSigningRelease.
            System.getenv("KEYSTORE_PATH")?.let {
                storeFile = file(it)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            // "0.1.0-debug.142+ab12cd3": which CI run and commit a tester has, shown in Settings.
            versionNameSuffix = "-debug" + (ciRun?.let { ".$it" } ?: "") + (ciCommit?.let { "+$it" } ?: "")
        }
        release {
            isMinifyEnabled = false
            if (System.getenv("KEYSTORE_PATH") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures { compose = true }

    // Room's exported schemas, for MigrationTestHelper. Robolectric reads the variant's assets, not
    // test-only ones, so they ride in the debug build (a few KB), never in release.
    sourceSets {
        getByName("debug").assets.srcDir("$projectDir/schemas")
        // Helpers both kinds of test need. Robolectric and a real device run the same ones, so a
        // device test doesn't start by copying them.
        getByName("test").kotlin.srcDir("src/sharedTest/java")
        getByName("androidTest").kotlin.srcDir("src/sharedTest/java")
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            all {
                // Robolectric's Android 16+ runtime needs this on JDK 21.
                it.jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
            }
        }
    }

    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/LICENSE*", "META-INF/NOTICE*")
        // Kokoro needs a 64-bit phone, so its ~20 MB of 32-bit native code would never run.
        jniLibs.excludes += (ABI_CODES.keys - ABI_64).flatMap { abi -> KOKORO_LIBS.map { "lib/$abi/$it" } }
    }

    // One APK per ABI: a debug build's podcast voice is about 30 MB of native code per 64-bit ABI, and a
    // single APK carried both. Each phone needs only its own. The 32-bit ones are kept, without
    // the voice, for older e-readers. Google Play builds per-device APKs from the bundle itself.
    splits {
        abi {
            isEnable = true
            reset()
            include(*ABI_CODES.keys.toTypedArray())
            isUniversalApk = false
        }
    }

    // F-Droid rejects the encrypted dependency blob Google Play wants.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// ScreenshotTest draws every screen for review and the README, with almost nothing asserted, so
// what it draws isn't counted as tested: it runs as its own task, which check depends on and
// Kover doesn't measure.
val screenshotTest = tasks.register<Test>("screenshotTest") {
    description = "Draws every screen to build/screenshots."
    group = "verification"
    val unitTests = tasks.named<Test>("testDebugUnitTest").get()
    testClassesDirs = unitTests.testClassesDirs
    classpath = unitTests.classpath
    jvmArgs(unitTests.jvmArgs)
    systemProperties(unitTests.systemProperties)
    filter.includeTestsMatching("*.ScreenshotTest")
}
tasks.withType<Test>().configureEach {
    if (name == "testDebugUnitTest" || name == "testReleaseUnitTest") filter.excludeTestsMatching("*.ScreenshotTest")
}
tasks.named("check") { dependsOn(screenshotTest) }
kover {
    currentProject { instrumentation { disabledForTestTasks.add("screenshotTest") } }
}

dependencies {
    implementation(project(":core"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime)
    implementation(libs.media3.session)
    implementation(libs.media3.common)
    implementation(libs.media3.exoplayer)
    implementation(libs.datastore.preferences)
    implementation(libs.coroutines.android)
    implementation(libs.okhttp)
    debugImplementation(libs.sherpa.onnx) { artifact { type = "aar" } }
    implementation(libs.jsoup)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.serialization.json)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.work.testing)
    testImplementation(libs.room.testing)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    // Compose UI test pulls an older Espresso that crashes on API 37.
    testImplementation(libs.espresso.core)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.espresso.core)
}

// The base is defaultConfig's version code, or for debug builds from CI the run number, so each
// build from CI installs over the one before it. Release builds keep their own base, which stores
// and F-Droid need to control.
androidComponents {
    onVariants { variant ->
        val debugRun = ciRun.takeIf { variant.buildType == "debug" }
        variant.outputs.forEach { output ->
            val abi = output.filters.singleOrNull { it.filterType == com.android.build.api.variant.FilterConfiguration.FilterType.ABI }?.identifier
                ?: return@forEach
            val base = debugRun ?: output.versionCode.orNull ?: 1
            output.versionCode.set(base * 10 + ABI_CODES.getValue(abi))
        }
        val manifestCheck = tasks.register<SelfUpdateManifestCheck>("check${variant.name.replaceFirstChar { it.uppercase() }}SelfUpdateManifest") {
            manifest.set(variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.MERGED_MANIFEST))
            selfUpdates.set(variant.buildType == "debug")
        }
        tasks.named("check") { dependsOn(manifestCheck) }
        // Reads the built APKs, so check assembles them. CI's `gradlew build` assembles them anyway.
        val kokoroCheck = tasks.register<KokoroNativeLibsCheck>("check${variant.name.replaceFirstChar { it.uppercase() }}KokoroNativeLibs") {
            apks.set(variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.APK))
            voice.set(variant.buildType == "debug")
            libs.set(KOKORO_LIBS)
            words.set(KOKORO_LIB_WORDS)
            abis.set(ABI_CODES.keys)
            sixtyFourBit.set(ABI_64)
        }
        tasks.named("check") { dependsOn(kokoroCheck) }
        if (variant.buildType == "release") {
            tasks.register<KokoroBundleCheck>("check${variant.name.replaceFirstChar { it.uppercase() }}KokoroBundle") {
                bundle.set(variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.BUNDLE))
                words.set(KOKORO_LIB_WORDS)
            }
        }
    }
}

/**
 * Fails when a variant's merged manifest disagrees about installing apps: the debug build updates
 * itself (src/debug), and Play and F-Droid refuse a release that could. Checking debug as well
 * keeps the release check from passing on a manifest it misread.
 */
abstract class SelfUpdateManifestCheck : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val manifest: RegularFileProperty

    @get:Input
    abstract val selfUpdates: Property<Boolean>

    @TaskAction
    fun check() {
        val text = manifest.get().asFile.readText()
        for (name in listOf("android.permission.REQUEST_INSTALL_PACKAGES", "update.InstallResultReceiver")) {
            if ((name in text) != selfUpdates.get()) {
                throw GradleException("${manifest.get().asFile}: $name should " + if (selfUpdates.get()) "be there" else "not be there")
            }
        }
    }
}

/**
 * Shared by the two checks below: what Kokoro's native code looks like inside a built artifact.
 *
 * A base class rather than a function in this script, because a task class that calls a
 * script-level function captures the script and Gradle then refuses to create it.
 */
abstract class KokoroLibsCheck : DefaultTask() {
    /** What makes a library part of Kokoro. See `KOKORO_LIB_WORDS`, which sets it. */
    @get:Input
    abstract val words: ListProperty<String>

    /**
     * The entry paths Kokoro's libraries sit at in [zip], which is an APK or a bundle. A bundle
     * keeps them under `base/lib/<abi>/` and an APK under `lib/<abi>/`, so the file name is what's
     * matched, not the whole path.
     */
    protected fun kokoroLibsIn(zip: File): Set<String> =
        ZipFile(zip).use { open ->
            open.entries().asSequence().map { it.name }
                .filter { entry ->
                    val name = entry.substringAfterLast('/')
                    entry.contains("lib/") && name.endsWith(".so") && words.get().any { it in name }
                }
                .toSortedSet()
        }
}

/**
 * Fails when a variant's APKs disagree about Kokoro's native code: the natural voice is offered in
 * debug builds only ([com.app.newspaperss.listen.KokoroSupport.inThisBuild]), so a release APK must
 * carry none of sherpa-onnx's libraries, and a 64-bit debug APK must carry all of them.
 *
 * Why it is worth a check of its own: v0.1.0 shipped those 32 MB to every 64-bit phone, where no
 * screen could reach them, because `debugImplementation` was `implementation`. One word puts them
 * back, and nothing on screen changes when it does. Checking debug as well keeps the release check
 * from passing because the libraries went missing altogether, and every ABI must bring an APK for
 * the same reason.
 *
 * The 32-bit APKs are held to the release rule in both build types: Kokoro needs a 64-bit phone, so
 * the packaging excludes its libraries there. That makes this a check on those excludes too.
 */
abstract class KokoroNativeLibsCheck : KokoroLibsCheck() {
    // NAME_ONLY, not NONE: every verdict below depends on each APK's file name.
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val apks: DirectoryProperty

    /** Whether this build type offers the voice: only then may the libraries be there at all. */
    @get:Input
    abstract val voice: Property<Boolean>

    /** The libraries a 64-bit APK of a build type that offers the voice must have, all of them. */
    @get:Input
    abstract val libs: ListProperty<String>

    /** Every ABI an APK is built for, so a name that says none of them, or a missing APK, fails. */
    @get:Input
    abstract val abis: SetProperty<String>

    @get:Input
    abstract val sixtyFourBit: SetProperty<String>

    @TaskAction
    fun check() {
        val apkFiles = apks.get().asFile.listFiles()?.filter { it.name.endsWith(".apk") }?.sorted().orEmpty()
        if (apkFiles.isEmpty()) throw GradleException("${apks.get().asFile}: no APKs to check")
        val seen = mutableSetOf<String>()
        for (apk in apkFiles) {
            // app-<abi>-<buildType>.apk, plus "-unsigned" when built without the release keystore.
            // Matched on the ABI between dashes: no ABI's name is part of another's that way.
            val abi = abis.get().singleOrNull { apk.name.contains("-$it-") }
                ?: throw GradleException("${apk.name}: can't tell which ABI this APK is for, so its native code can't be checked")
            seen += abi
            val found = kokoroLibsIn(apk)
            val expected: Set<String> =
                if (voice.get() && abi in sixtyFourBit.get()) libs.get().map { "lib/$abi/$it" }.toSortedSet() else sortedSetOf()
            if (found != expected) {
                val what = if (expected.isEmpty()) "should carry none of Kokoro's native code" else "should carry ${expected.size} of Kokoro's libraries"
                throw GradleException("${apk.name} $what, and has ${found.size}: ${found.joinToString()}")
            }
        }
        val missing = abis.get() - seen
        if (missing.isNotEmpty()) throw GradleException("no APK to check for ${missing.sorted().joinToString()}: expected one per ABI")
    }
}

/**
 * The same rule for the bundle, which is what Google Play receives and explodes into per-device
 * APKs: a release AAB carries no part of Kokoro.
 *
 * Its own task because `check` would otherwise have to build the bundle, which nothing else there
 * needs. The Release workflow runs it beside `bundleRelease`, where the bytes it reads are the
 * bytes that go to Play.
 */
abstract class KokoroBundleCheck : KokoroLibsCheck() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val bundle: RegularFileProperty

    @TaskAction
    fun check() {
        val aab = bundle.get().asFile
        val found = kokoroLibsIn(aab)
        if (found.isNotEmpty()) {
            throw GradleException("${aab.name} should carry none of Kokoro's native code, and has ${found.size}: ${found.joinToString()}")
        }
    }
}
