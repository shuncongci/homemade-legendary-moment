plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "xyz.shuncongci.leica"
    compileSdk = 35
    defaultConfig {
        applicationId = "xyz.shuncongci.leica"
        minSdk = 26
        targetSdk = 35
        versionCode = 38
        versionName = "2.1.23"
        testInstrumentationRunner = "xyz.shuncongci.leica.HairDiagnosticInstrumentation"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging {
        // Keep dependency license/notice resources in the public build.
        resources.excludes += "/META-INF/DEPENDENCIES"
        jniLibs.useLegacyPackaging = false
    }
    sourceSets.getByName("main").assets.exclude("leica/leica-m9-v3-epoch30.fp16.onnx", "leica/legendary-1.0-gan.fp16.onnx", "leica/leica-modern-*.onnx", "leica/leica-vintage-*.onnx", "leica/legendary-1.0-depth.fp16.onnx", "leica/legendary-1.0-depth-native-chunk64.fp16.onnx", "legendary-runtime-licenses/**", "legendary-ort-qnn/**", "legendary-runtime-notice.txt")

    androidResources {
        noCompress += "onnx"
        // Asset merging does not consistently honor SourceDirectorySet excludes; filter by basename here too.
        // Keep the legacy file in source, but only package the active M9 v3 GAN.
        ignoreAssetsPattern = "leica-m9-v3-epoch30.fp16.onnx:legendary-1.0-gan.fp16.onnx:legendary-1.0-depth.fp16.onnx:legendary-1.0-depth-native-chunk64.fp16.onnx"
    }
}
dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Override only for an explicitly prepared offline dependency; no AAR is bundled here.
    val localOrt = providers.gradleProperty("onnxruntimeAar").orNull
    if (localOrt != null) implementation(files(localOrt))
    else implementation("com.microsoft.onnxruntime:onnxruntime-android:1.28.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")
    testImplementation("junit:junit:4.13.2")
}

// A direct JUnit entry point, useful on hosts where the Gradle Test worker has path issues.
// Runs the same assertions as the standard unit-test task.
val collectLeicaTestJars by tasks.registering(Copy::class) {
    from(configurations.getByName("debugUnitTestRuntimeClasspath"))
    include("junit-*.jar", "hamcrest-core-*.jar", "kotlin-stdlib-*.jar")
    into(layout.buildDirectory.dir("leica-test-libs"))
}
tasks.register<JavaExec>("verifyLeicaMath") {
    group = "verification"
    description = "Runs all Leica image-math and 2048-fit whole-frame, viewport and hardware-policy JUnit tests directly"
    dependsOn("compileDebugUnitTestKotlin", collectLeicaTestJars)
    classpath = files(
        layout.buildDirectory.dir("tmp/kotlin-classes/debugUnitTest"),
        layout.buildDirectory.dir("tmp/kotlin-classes/debug"),
        "src/test/resources"
    ) + fileTree(layout.buildDirectory.dir("leica-test-libs")) { include("*.jar") }
    mainClass.set("org.junit.runner.JUnitCore")
    args("xyz.shuncongci.leica.PhotoOutputPolicyTest", "xyz.shuncongci.leica.SubjectContactTest", "xyz.shuncongci.leica.SubjectMatteTilesTest", "xyz.shuncongci.leica.EdgeDecontaminationTest", "xyz.shuncongci.leica.CleanPlateBokehTest", "xyz.shuncongci.leica.SoftBokehEdgesTest", "xyz.shuncongci.leica.ReflectionEdgeTest", "xyz.shuncongci.leica.NaturalBokehTest", "xyz.shuncongci.leica.HighlightSurfaceTest", "xyz.shuncongci.leica.PupilKernelExactTest", "xyz.shuncongci.leica.HighlightSpeedTest", "xyz.shuncongci.leica.BokehRadianceTest", "xyz.shuncongci.leica.SubjectLiftTest", "xyz.shuncongci.leica.SubjectRequestRegressionTest", "xyz.shuncongci.leica.SubjectIncrementalTest", "xyz.shuncongci.leica.PointDragTest", "xyz.shuncongci.leica.WarmHighlightTest", "xyz.shuncongci.leica.SubjectMultiPointTest", "xyz.shuncongci.leica.HighlightRadiusTest", "xyz.shuncongci.leica.SubjectMotionTest", "xyz.shuncongci.leica.OpticalPupilTest", "xyz.shuncongci.leica.SubjectProtectionTest", "xyz.shuncongci.leica.EditorIntegrationTest", "xyz.shuncongci.leica.GaicCompositionTest", "xyz.shuncongci.leica.DevelopTransferTest", "xyz.shuncongci.leica.DevelopMotionTest", "xyz.shuncongci.leica.CompositionTileMotionTest", "xyz.shuncongci.leica.PhotoWorkflowTest", "xyz.shuncongci.leica.CompositionOutputTest", "xyz.shuncongci.leica.CompositionTest", "xyz.shuncongci.leica.ProgressiveDepthTest", "xyz.shuncongci.leica.UiTextTest", "xyz.shuncongci.leica.FullFrameOpticsTest", "xyz.shuncongci.leica.DepthVisualTest", "xyz.shuncongci.leica.DiagnosticMathTest", "xyz.shuncongci.leica.FocusRangeTest", "xyz.shuncongci.leica.EditorLayoutTest", "xyz.shuncongci.leica.HighlightScatterTest", "xyz.shuncongci.leica.FocusPreviewTest", "xyz.shuncongci.leica.PhotoCopyTest", "xyz.shuncongci.leica.LeicaMathTest", "xyz.shuncongci.leica.LegendaryMathTest", "xyz.shuncongci.leica.PhotoFlowTest", "xyz.shuncongci.leica.NativeImageMathTest", "xyz.shuncongci.leica.PreviewViewportTest", "xyz.shuncongci.leica.HardwarePolicyTest", "xyz.shuncongci.leica.BaselineOutputTest", "xyz.shuncongci.leica.NativeLifecycleTest", "xyz.shuncongci.leica.GeluCompatibilityTest", "xyz.shuncongci.leica.NativeDepthMathTest")
    maxHeapSize = "2g"
    jvmArgs("-Dfile.encoding=UTF-8")
}