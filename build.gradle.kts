// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}

// Optional external output directory for local verification; normally Gradle uses build/.
providers.gradleProperty("buildOutputRoot").orNull?.let { output ->
    val base = rootProject.file(output)
    layout.buildDirectory.set(base.resolve("root"))
    subprojects { layout.buildDirectory.set(base.resolve(project.name)) }
}
