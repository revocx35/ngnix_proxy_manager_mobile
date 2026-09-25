// Pure Kotlin/JVM: the Nginx Proxy Manager API contract (models), the HTTP client, error mapping and
// form validation. No Android dependencies, so all of it is unit-tested on the JVM (also against a live NPM).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    api(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

tasks.test {
    // LiveNpmTest runs only when NPM_IT_URL / NPM_IT_EMAIL / NPM_IT_PASSWORD are set (see CLAUDE.md).
    listOf("NPM_IT_URL", "NPM_IT_EMAIL", "NPM_IT_PASSWORD", "NPM_PROBE_URL").forEach { key ->
        System.getenv(key)?.let { environment(key, it) }
    }
    inputs.property("liveTarget", System.getenv("NPM_IT_URL") ?: "")
    testLogging { events("failed", "skipped"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
