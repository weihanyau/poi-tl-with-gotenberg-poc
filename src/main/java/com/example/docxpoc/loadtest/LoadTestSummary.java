package com.example.docxpoc.loadtest;

import java.util.Arrays;
import java.util.List;

/** Aggregated result of a run. Serialised straight to JSON by the status endpoint. */
public record LoadTestSummary(
        String runId,
        String mode,
        String status,
        int requested,
        int completed,
        int succeeded,
        int failed,
        int inFlight,
        int concurrency,
        boolean renderPerRequest,
        long wallClockMs,
        double throughputPerSecond,
        long latencyMinMs,
        long latencyP50Ms,
        long latencyP95Ms,
        long latencyP99Ms,
        long latencyMaxMs,
        double latencyMeanMs,
        double totalPdfMegabytes,
        String csvPath,
        List<String> sampleErrors) {

    static LoadTestSummary from(LoadTestRun run) {
        List<RequestRecord> records = run.recordsSnapshot();

        long[] latencies = records.stream()
                .filter(RequestRecord::succeeded)
                .mapToLong(RequestRecord::latencyMs)
                .sorted()
                .toArray();

        int succeeded = latencies.length;
        int failed = records.size() - succeeded;
        long wallClockMs = run.wallClockMs();
        long totalBytes = records.stream().mapToLong(RequestRecord::pdfBytes).sum();

        // Throughput is measured over successful conversions only; counting failures
        // would flatter a run whose backend was rejecting requests instantly.
        double throughput = wallClockMs > 0 ? succeeded * 1000.0 / wallClockMs : 0.0;
        double mean = succeeded > 0 ? Arrays.stream(latencies).average().orElse(0.0) : 0.0;

        List<String> sampleErrors = records.stream()
                .filter(record -> !record.succeeded())
                .map(RequestRecord::error)
                .distinct()
                .limit(5)
                .toList();

        return new LoadTestSummary(
                run.getRunId(),
                run.getMode().name().toLowerCase(),
                run.getStatus(),
                run.getRequested(),
                records.size(),
                succeeded,
                failed,
                run.inFlightCount(),
                run.getConcurrency(),
                run.isRenderPerRequest(),
                wallClockMs,
                round(throughput),
                percentile(latencies, 0.0),
                percentile(latencies, 0.50),
                percentile(latencies, 0.95),
                percentile(latencies, 0.99),
                percentile(latencies, 1.0),
                round(mean),
                round(totalBytes / 1024.0 / 1024.0),
                run.getCsvPath(),
                sampleErrors);
    }

    /** Nearest-rank percentile over a pre-sorted array. */
    private static long percentile(long[] sorted, double fraction) {
        if (sorted.length == 0) {
            return 0L;
        }
        int index = (int) Math.ceil(fraction * sorted.length) - 1;
        return sorted[Math.min(Math.max(index, 0), sorted.length - 1)];
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
