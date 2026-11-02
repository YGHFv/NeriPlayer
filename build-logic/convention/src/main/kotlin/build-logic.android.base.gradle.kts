@file:Suppress("UnstableApiUsage")

import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.tasks.testing.Test

plugins {
    id("com.android.base")
}

fun CommonExtension.configureAndroidBase() {
    compileSdk = Version.compileSdkVersion
    ndkVersion = Version.getNdkVersion()

    defaultConfig.minSdk = Version.minSdk

    compileOptions.sourceCompatibility = Version.java
    compileOptions.targetCompatibility = Version.java
}

extensions.findByType(ApplicationExtension::class)?.run {
    configureAndroidBase()
    defaultConfig.targetSdk = Version.targetSdk
}

extensions.findByType(LibraryExtension::class)?.configureAndroidBase()

// Robolectric downloads its Android runtime jars independently from Gradle.
// Use the domestic mirror by default; projects can override both properties.
tasks.withType<Test>().configureEach {
    systemProperty(
        "robolectric.dependency.repo.id",
        providers.gradleProperty("robolectricDependencyRepoId").orElse("aliyun").get()
    )
    systemProperty(
        "robolectric.dependency.repo.url",
        providers.gradleProperty("robolectricDependencyRepoUrl")
            .orElse("https://maven.aliyun.com/repository/public")
            .get()
    )
}
