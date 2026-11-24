plugins {
    alias(libs.plugins.kotlinJvm)
}

group = "ai.rever.boss.ui"
version = "1.0.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    implementation(project(":boss-ipc"))
    // api, not implementation: ScrollCoalescer.coalesce is typed on Flow in both directions, so a
    // consumer of the published jar needs coroutines on its own compile classpath to call it.
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test.junit)
    // ScrollCoalescer's contract is "one event per window, and the resting position always arrives" —
    // both are statements about time, so they are asserted on runTest's virtual clock rather than by
    // sleeping and hoping.
    testImplementation(libs.kotlinx.coroutines.test)
}
