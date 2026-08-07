package com.norfold.app.collaboration.benchmark

import kotlinx.coroutines.runBlocking

/**
 * Standalone JVM entry point for the benchmark harness.
 *
 * Usage (from project root):
 *   export NORFOLD_SUPABASE_URL=https://xxx.supabase.co
 *   export NORFOLD_SUPABASE_ANON_KEY=eyJ...
 *   export NORFOLD_CF_DO_URL=https://xxx.workers.dev
 *   export NORFOLD_ABLY_BENCH_TOKEN=<ably-token>
 *   ./gradlew :apps:android:testDebugUnitTest --tests "*.BenchmarkRunnerTest"
 *
 * Missing env vars cause that provider to be SKIPPED, not to fail the run.
 */
fun main() = runBlocking {
    println("═══════════════════════════════════════════════════")
    println("  Norfold Collaboration Transport Benchmark")
    println("═══════════════════════════════════════════════════")
    val results = BenchmarkHarness.runAll()
    println()
    results.forEach { it.print() }
    println()
    printSummary(results)
}

private fun printSummary(results: List<BenchmarkHarness.ProviderResult>) {
    println("═══════════════════════════════════════════════════")
    println("  SUMMARY")
    println("═══════════════════════════════════════════════════")
    val ok = results.filter { !it.skipped && it.error == null }
    if (ok.isEmpty()) {
        println("  No providers produced results. Set env vars and re-run.")
        return
    }
    val best = ok.minByOrNull { it.latency?.p50 ?: Double.MAX_VALUE }
    println("  Lowest p50 latency : ${best?.provider ?: "N/A"}")
    ok.sortedBy { it.latency?.p50 ?: Double.MAX_VALUE }.forEachIndexed { i, r ->
        val p50 = r.latency?.p50?.let { "%.1f ms".format(it) } ?: "N/A"
        println("  ${i + 1}. ${r.provider.padEnd(28)} p50=$p50")
    }
    println()
    println("  PROVIDER DECISION: pending device-side validation")
    println("═══════════════════════════════════════════════════")
}
