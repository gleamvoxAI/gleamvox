import java.security.MessageDigest
import java.util.Properties

import com.android.build.api.variant.ApplicationVariant

fun sha256Of(file: File): String =
    MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

plugins {
    id("com.android.application")
    kotlin("plugin.serialization") version "2.4.0"
    kotlin("plugin.compose") version "2.4.0"
}

// number of commits in this repository, 0 if git is not available
val gitCommitCount: Int = try {
    providers.exec { commandLine("git", "-C", rootDir.absolutePath, "rev-list", "--count", "HEAD") }
        .standardOutput.asText.get().trim().toInt()
} catch (_: Exception) { 0 }

android {
    compileSdk = 36

    defaultConfig {
        applicationId = "helium314.keyboard"
        minSdk = 21
        targetSdk = 36
        versionCode = 4201 + gitCommitCount // grows with every commit, so AppUpgrade clears the dictionary cache on update
        versionName = "4.2 build ${4201 + gitCommitCount}" // build number = versionCode, shown on the About screen
        ndk {
            abiFilters.clear()
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64"))
        }
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        // AI functions with the own OpenAI API key (docs/AI.md): only in the personal variant, which also gets the INTERNET permission
        buildConfigField("boolean", "AIKB_OPENAI", "false")
        // play-1: the fresh screenshot (READ_MEDIA_IMAGES) is not in the Play version until Google approves it
        buildConfigField("boolean", "AIKB_SCREENSHOT", "true")
        // play-1: may the user load a gesture typing library (native code from a file)? Never in the Play version: Google
        // Play forbids executable code from outside the store (Device and Network Abuse policy); "nouserlib" did the same
        buildConfigField("boolean", "USER_GESTURE_LIB", "true")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            isDebuggable = false
            isJniDebuggable = false
        }
        create("nouserlib") { // same as release, but does not allow the user to provide a library
            buildConfigField("boolean", "USER_GESTURE_LIB", "false")
            isMinifyEnabled = true
            isShrinkResources = false
            isDebuggable = false
            isJniDebuggable = false
        }
        debug {
            // "normal" debug has minify for smaller APK to fit the GitHub 25 MB limit when zipped
            // and for better performance in case users want to install a debug APK
            isMinifyEnabled = true
            isJniDebuggable = false
            applicationIdSuffix = ".debug"
        }
        create("runTests") { // build variant for running tests on CI that skips tests known to fail
            isMinifyEnabled = false
            isJniDebuggable = false
        }
        // play-1 (docs/DECISIONS.md): the store version "Gleamvox" (com.gleamvox.keyboard), an Android App Bundle for
        // Google Play: release parts only, not debuggable, signed with the upload key (Play App Signing keeps the app key).
        // Not minified: R8 over the whole app does not fit the memory of the build server (Gradle -Xmx1g), and the size
        // matters less than a build that works (owner's principle 2026-10-17)
        create("play") {
            isDebuggable = false
            isMinifyEnabled = false
            isShrinkResources = false
            isJniDebuggable = false
            buildConfigField("boolean", "AIKB_SCREENSHOT", "false")
            buildConfigField("boolean", "USER_GESTURE_LIB", "false")
            matchingFallbacks += listOf("release")
        }
        create("debugNoMinify") { // for faster builds in IDE
            isDebuggable = true
            isMinifyEnabled = false
            isJniDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".debug"
            isDefault = true
        }

        androidComponents.onVariants { variant: ApplicationVariant ->
            if (variant.buildType == "play") {
                // the store package and version: its own, never helium314.keyboard (that is HeliBoard's)
                variant.applicationId.set("com.gleamvox.keyboard")
                variant.outputs.forEach { it.versionName.set("1.0.${4201 + gitCommitCount}") }
            }
            if (variant.buildType == "debug") {
                // got a little too big for GitHub after some dependency upgrades, so we remove the largest dictionary
                variant.androidResources.ignoreAssetsPatterns = listOf("main_ro.dict")
                variant.proguardFiles = emptyList()
                //noinspection ProguardAndroidTxtUsage we intentionally use the "normal" file here
                variant.proguardFiles.add(project.layout.buildDirectory.file(project.buildFile.parent + "/dontoptimize.pro"))
                variant.proguardFiles.add(project.layout.buildDirectory.file(project.buildFile.parent + "/proguard-rules.pro"))
            }
            variant.outputs.forEach { output ->
                if (output is com.android.build.api.variant.impl.VariantOutputImpl) {
                    output.outputFileName = "HeliBoard_4.2-${variant.buildType}.apk" // fixed name, copyAikbApk expects one APK
                }
            }
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        compose = true
    }

    externalNativeBuild {
        ndkBuild {
            path = File("src/main/jni/Android.mk")
        }
    }
    ndkVersion = "28.0.13004108"

    // ---- Two build variants (see docs/DECISIONS.md) ----
    // default ("release" variant): only HeliBoard parts, no closed-source or third-party files from outside git.
    // -PaikbPersonal=true ("personal" variant, only for the owner's phone):
    //   * closed-source Google swipe library -> lib/arm64-v8a/libjni_latinimegoogle.so (not stored in git)
    //   * experimental main dictionaries (ru, en-US) replacing the built-in ones, plus emoji dictionaries (ru, en)
    // Local files live in ~/.local/ai-keyboard-tools (change with -PswypeLibsDir=..., -PaikbDictsDir=...).
    val aikbPersonal = (findProperty("aikbPersonal") as String?)?.toBoolean() ?: false
    val aikbPlay = (findProperty("aikbPlay") as String?)?.toBoolean() ?: false
    val toolsDir = "${System.getProperty("user.home")}/.local/ai-keyboard-tools"

    if (aikbPersonal) {
        val swypeLibsDir = File((findProperty("swypeLibsDir") as String?) ?: "$toolsDir/swypelibs")
        val swypeLib = File(swypeLibsDir, "arm64-v8a/libjni_latinimegoogle.so")
        // must be equal to CHECKSUM_ARM64 in JniUtils.java
        val swypeLibSha256 = "b1049983e6ac5cfc6d1c66e38959751044fad213dff0637a6cf1d2a2703e754f"
        if (!swypeLib.isFile) {
            logger.warn("WARNING: $swypeLib not found, building WITHOUT swipe typing library")
        } else {
            val actual = sha256Of(swypeLib)
            if (actual != swypeLibSha256)
                logger.warn("WARNING: $swypeLib has wrong SHA-256 ($actual), building WITHOUT swipe typing library")
            else
                sourceSets["main"].jniLibs.srcDir(swypeLibsDir)
        }

        // personal is not debuggable (the OpenAI API key, docs/AI.md) and signed with a local key.
        // The key and its parameters live outside git; the passwords are never printed.
        val signingFile = File((findProperty("aikbSigningProperties") as String?)
            ?: "${System.getProperty("user.home")}/.secrets/aikb-signing.properties")
        if (!signingFile.isFile)
            throw GradleException("aikbPersonal: signing parameters not found: $signingFile " +
                "(keys storeFile, storePassword, keyAlias, keyPassword; another file can be set with -PaikbSigningProperties=...)")
        val signingProps = Properties().apply { signingFile.inputStream().use { load(it) } }
        fun signingValue(key: String) = signingProps.getProperty(key)?.takeIf { it.isNotBlank() }
            ?: throw GradleException("aikbPersonal: '$key' missing in $signingFile")
        val personalSigning = signingConfigs.create("aikbPersonal") {
            storeFile = File(signingValue("storeFile"))
            storePassword = signingValue("storePassword")
            keyAlias = signingValue("keyAlias")
            keyPassword = signingValue("keyPassword")
        }
        if (!personalSigning.storeFile!!.isFile)
            throw GradleException("aikbPersonal: key store not found: ${personalSigning.storeFile}")
        buildTypes.getByName("debugNoMinify") {
            isDebuggable = false
            signingConfig = personalSigning
            buildConfigField("boolean", "AIKB_OPENAI", "true")
        }
        // non-debuggable builds also run "lint vital", which downloads extra lint libraries; the other variant never ran it
        lint { checkReleaseBuilds = false }
        // adds the INTERNET permission, only here
        sourceSets["debugNoMinify"].manifest.srcFile("src/aikbPersonal/AndroidManifest.xml")
        // stage 5g-19: the Silero VAD model (MIT, v6.2.3, docs/DECISIONS.md), only where the OpenAI voice is
        sourceSets["debugNoMinify"].assets.srcDir("src/aikbPersonal/assets")
        // ONNX Runtime only for arm64-v8a: personal is for the owner's phone (arm64, like the swipe library); with all four
        // ABIs the APK was 88 MB, over the 50 MB that tools/aikb/send-apk.sh (Telegram) can send. Elsewhere: WebRTC VAD
        packaging.jniLibs.excludes += listOf("armeabi-v7a", "x86", "x86_64").flatMap { abi ->
            listOf("lib/$abi/libonnxruntime.so", "lib/$abi/libonnxruntime4j_jni.so") }
    }

    if (aikbPersonal || aikbPlay) {
        // dictionaries: copied (after SHA-256 check against docs/DICTIONARIES.sha256) to a build folder that
        // overrides the built-in assets/dicts files of the same name
        val dictsDir = File((findProperty("aikbDictsDir") as String?) ?: "$toolsDir/dictionaries")
        val dictsOut = File(layout.buildDirectory.asFile.get(), "aikb-personal-assets")
        val checksumFile = rootProject.file("docs/DICTIONARIES.sha256")
        // source file -> name inside the APK (assets/dicts/<type>_<locale>.dict)
        val dictTargets = mapOf(
            "main_ru__experimental.dict" to "main_ru.dict",
            "main_en_us__experimental.dict" to "main_en-US.dict",
            "emoji_ru.dict" to "emoji_ru.dict",
            "emoji_en.dict" to "emoji_en.dict",
        ) + run {
            // lang-1: 13 more languages (word lists CC BY 4.0 / CC BY-SA 4.0, docs/DICTIONARIES.md); the name inside the APK
            // is the locale of the layout (Hebrew "iw" and Indonesian "in", the old codes Android uses)
            val more = listOf("uk" to "uk__experimental", "kk" to "kk__experimental", "ar" to "ar__experimental",
                "in" to "id__experimental", "cs" to "cs__experimental", "sk" to "sk__experimental", "hr" to "hr__experimental",
                "fi" to "fi__experimental", "da" to "da__experimental", "nb" to "nb__experimental",
                "iw" to "he__experimental", "fa" to "fa_ir__experimental", "az" to "az")
            more.associate { (locale, source) -> "main_$source.dict" to "main_$locale.dict" } +
                more.associate { (locale, source) -> "emoji_${source.substringBefore("_")}.dict" to "emoji_$locale.dict" }
        }
        val prepareDicts = tasks.register("prepareAikbDictionaries") {
            inputs.file(checksumFile)
            inputs.property("dictsDir", dictsDir.path)
            inputs.files(dictTargets.keys.map { File(dictsDir, it) })
            outputs.dir(dictsOut)
            doLast {
                val expected = checksumFile.readLines().filter { it.isNotBlank() }
                    .associate { it.substringAfter("  ").trim() to it.substringBefore("  ").trim() }
                val out = File(dictsOut, "dicts")
                out.deleteRecursively()
                out.mkdirs()
                dictTargets.forEach { (source, target) ->
                    val file = File(dictsDir, source)
                    if (!file.isFile)
                        throw GradleException("dictionaries: dictionary file not found: $file (folder can be set with -PaikbDictsDir=...)")
                    val want = expected[source]
                        ?: throw GradleException("dictionaries: no checksum for $source in ${checksumFile.path}")
                    // inline digest: script-level functions break the configuration cache
                    val actual = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                        .joinToString("") { "%02x".format(it) }
                    if (actual != want)
                        throw GradleException("dictionaries: wrong SHA-256 of $file: expected $want, got $actual")
                    file.copyTo(File(out, target))
                }
            }
        }
        if (aikbPersonal) {
            sourceSets["debugNoMinify"].assets.srcDir(dictsOut)
            tasks.matching { it.name == "mergeDebugNoMinifyAssets" }.configureEach { dependsOn(prepareDicts) }
        }
        if (aikbPlay) { // play-2: the same files and the same SHA-256 check in the store version
            sourceSets["play"].assets.srcDir(dictsOut)
            tasks.matching { it.name == "mergePlayAssets" }.configureEach { dependsOn(prepareDicts) }
        }
    }

    // play-1: the upload key of Google Play, outside the repository (tools/aikb/make-upload-key.sh makes it). Only read
    // when the store version is built (-PaikbPlay=true, tools/aikb/build.sh play); never together with personal.
    if (aikbPlay) {
        if (aikbPersonal) throw GradleException("aikbPlay and aikbPersonal together: the store version never gets personal parts")
        val keysDir = File((findProperty("aikbKeysDir") as String?) ?: "${System.getProperty("user.home")}/.local/ai-keyboard-keys")
        val propsFile = File(keysDir, "upload-signing.properties")
        if (!propsFile.isFile)
            throw GradleException("play: $propsFile not found; run tools/aikb/make-upload-key.sh first")
        val props = Properties().apply { propsFile.inputStream().use { load(it) } }
        val upload = signingConfigs.create("gleamvoxUpload") {
            storeFile = File(props.getProperty("storeFile"))
            storePassword = props.getProperty("storePassword")
            keyAlias = props.getProperty("keyAlias")
            keyPassword = props.getProperty("keyPassword")
            storeType = "PKCS12"
        }
        buildTypes.getByName("play") { signingConfig = upload }
        // non-debuggable builds run "lint vital" too: as for personal, off (memory of the server)
        lint { checkReleaseBuilds = false }
    }

    // play-4: only for tools/aikb/build.sh shots: the debug unit tests (the only ones AGP makes) draw the keyboard with the
    // names and strings of the store version: the debug names (src/debug/res/values/strings.xml) are replaced by those of
    // src/play/res, the other two debug files are copied by build.sh shots to build/aikb-store-res
    if ((findProperty("aikbStoreShots") as String?).isNullOrEmpty().not())
        sourceSets["debug"].res.setSrcDirs(listOf("src/play/res", "build/aikb-store-res"))

    // Debug APKs are packaged incrementally, i.e. on top of the previous APK, which leaves dead (unreferenced) bytes
    // of the previous variant inside the file. The variant name is an input of the packaging task, so switching
    // between personal and release makes Gradle repackage from scratch and release never carries personal leftovers.
    tasks.matching { it.name == "packageDebugNoMinify" }.configureEach {
        inputs.property("aikbVariant", if (aikbPersonal) "personal" else "release")
    }

    // copies the result to app/build/outputs/aikb/AIKeyboard-<personal|release>.apk
    val variantName = if (aikbPersonal) "personal" else "release"
    val copyAikbApk = tasks.register("copyAikbApk") {
        val apkDir = layout.buildDirectory.dir("outputs/apk/debugNoMinify")
        val outDir = layout.buildDirectory.dir("outputs/aikb")
        doLast {
            val apk = apkDir.get().asFile.listFiles { f -> f.name.endsWith(".apk") }?.singleOrNull()
                ?: throw GradleException("expected exactly one APK in ${apkDir.get().asFile}")
            outDir.get().asFile.mkdirs()
            apk.copyTo(File(outDir.get().asFile, "AIKeyboard-$variantName.apk"), overwrite = true)
        }
    }
    tasks.matching { it.name == "assembleDebugNoMinify" }.configureEach { finalizedBy(copyAikbApk) }

    // play-1: the bundle and the APK of the store version to app/build/outputs/aikb/Gleamvox-play.(aab|apk)
    val copyPlay = tasks.register("copyAikbPlay") {
        val outDir = layout.buildDirectory.dir("outputs/aikb")
        val bundleDir = layout.buildDirectory.dir("outputs/bundle/play")
        val apkDir = layout.buildDirectory.dir("outputs/apk/play")
        doLast {
            outDir.get().asFile.mkdirs()
            bundleDir.get().asFile.listFiles { f -> f.name.endsWith(".aab") }?.singleOrNull()
                ?.copyTo(File(outDir.get().asFile, "Gleamvox-play.aab"), overwrite = true)
            apkDir.get().asFile.listFiles { f -> f.name.endsWith(".apk") }?.singleOrNull()
                ?.copyTo(File(outDir.get().asFile, "Gleamvox-play.apk"), overwrite = true)
        }
    }
    tasks.matching { it.name == "bundlePlay" || it.name == "assemblePlay" }.configureEach { finalizedBy(copyPlay) }

    packaging {
        jniLibs {
            // shrinks APK by 3 MB, zipped size unchanged
            useLegacyPackaging = true
            // keep the closed-source swipe library byte-identical (no strip), its SHA-256 is verified
            keepDebugSymbols += "**/libjni_latinimegoogle.so"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // one small test JVM at a time: the build server has 3.8 GB RAM shared with other projects
            all {
                it.maxParallelForks = 1
                it.maxHeapSize = "512m" // 768m: the test JVM always filled the whole heap, free memory on the server fell to 545 MB
                // outside the heap the test JVM held about 1 GB more (Robolectric classes, JIT code, native memory of
                // 150 threads); only the quick C1 JIT and two malloc arenas keep that smaller
                it.jvmArgs("-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1")
                it.environment("MALLOC_ARENA_MAX", "2")
                // a new test JVM every 10 test classes: one JVM for all 53 classes grew to 1.7 GB RSS, Robolectric classes pile up (metaspace 380 MB after 20 classes)
                it.forkEvery = 10
                // ai-dict-1: the comparison of language models runs only from tools/aikb/build.sh lmeval
                it.systemProperty("aikb.lmEval", (project.findProperty("aikbLmEval") as String?) ?: "")
                // play-4: the store pictures are drawn only from tools/aikb/build.sh shots (StoreShotsTest)
                it.systemProperty("aikb.storeShots", (project.findProperty("aikbStoreShots") as String?) ?: "")
                // swipe-4: our glide typing on the real swipes of the FUTO dataset (MIT), only from tools/aikb/build.sh swipebench
                it.systemProperty("aikb.swipeBench", (project.findProperty("aikbSwipeBench") as String?) ?: "")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // see https://github.com/HeliBorg/HeliBoard/issues/477
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    namespace = "helium314.keyboard.latin"
    lint {
        abortOnError = true
    }
}

dependencies {
    // androidx
    implementation("androidx.core:core-ktx:1.17.0") // 1.18.0 requires minSdk 23
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.autofill:autofill:1.3.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")

    // kotlin
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // compose
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation(platform("androidx.compose:compose-bom:2025.11.01")) // newer requires minSdk 23
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    "debugNoMinifyImplementation"("androidx.compose.ui:ui-tooling")
    implementation("androidx.navigation:navigation-compose:2.9.8")
    implementation("sh.calvin.reorderable:reorderable:3.1.0") // for easier re-ordering
    implementation("com.github.skydoves:colorpicker-compose:1.1.3") // for user-defined colors, newer requires minSdk 23

    // stage 5g-19: ONNX Runtime (MIT) for the Silero speech detector, only in the personal variant (the only one with
    // the OpenAI voice); its telemetry provider and network permissions are removed in src/aikbPersonal/AndroidManifest.xml
    compileOnly("com.microsoft.onnxruntime:onnxruntime-android:1.30.0")
    if ((findProperty("aikbPersonal") as String?)?.toBoolean() == true)
        implementation("com.microsoft.onnxruntime:onnxruntime-android:1.30.0")
    // the same runtime for the JVM: the tests run the real model
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.30.0")

    // test
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit")
    testImplementation("org.mockito:mockito-core:5.23.0")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:runner:1.7.0")
    testImplementation("androidx.test:core:1.7.0")
}
