package com.norfold.app.data

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the installable, release-configuration build lane that CI must keep producing. */
class ReleaseCertificationLaneTest {
    private fun read(vararg candidates: String): String {
        val paths = candidates.flatMap { listOf(File(it), File("apps/android/$it")) }
        return (paths.firstOrNull(File::isFile) ?: error("not found: ${candidates.joinToString()}")).readText()
    }

    private val buildFile by lazy { read("build.gradle.kts") }
    private val workflow by lazy { read("../../.github/workflows/build.yml", ".github/workflows/build.yml") }

    @Test
    fun benchmarkInheritsTheHardenedReleaseConfiguration() {
        val benchmark = buildFile.substringAfter("create(\"benchmark\")").substringBefore("sourceSets")
        assertTrue(benchmark.contains("initWith(getByName(\"release\"))"))
        assertTrue(benchmark.contains("matchingFallbacks += listOf(\"release\")"))
        assertTrue(
            "Benchmark must remain installable without adding a production signing identity",
            benchmark.contains("ciDevelopment") && benchmark.contains("else \"debug\""),
        )

        val release = buildFile.substringAfter("release {").substringBefore("create(\"benchmark\")")
        assertTrue(release.contains("isMinifyEnabled = true"))
        assertTrue(release.contains("isShrinkResources = true"))
        assertTrue(release.contains("ALLOW_DESTRUCTIVE_SCHEMA_RESET\", \"false\""))
        assertFalse(
            "Production release signing must not be introduced by the certification lane",
            release.lineSequence().any { it.trimStart().startsWith("signingConfig =") },
        )
    }

    @Test
    fun ciBuildsAndPublishesTheInstallableBenchmarkApk() {
        assertTrue(
            "CI must execute the release-configuration certification build",
            workflow.contains(":apps:android:assembleBenchmark"),
        )
        assertTrue(
            "CI must retain the benchmark APK so a later device gate runs the exact certified artifact",
            workflow.contains("apps/android/build/outputs/apk/benchmark/*.apk"),
        )
        assertTrue(workflow.contains("norfold-android-benchmark-"))
    }
}
