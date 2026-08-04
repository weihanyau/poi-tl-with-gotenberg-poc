package com.example.docxpoc.loadtest;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

import lombok.Getter;

/** Mutable state of a single load test run, shared between the driver and webhook callbacks. */
@Getter
public class LoadTestRun {

    public enum Mode {
        /** Blocking HTTP call per conversion. */
        SYNC,
        /** Gotenberg POSTs the finished PDF back to a callback endpoint. */
        WEBHOOK
    }

    private final String runId;
    private final Mode mode;
    private final int requested;
    private final int concurrency;
    private final boolean renderPerRequest;

    private final Queue<RequestRecord> records = new ConcurrentLinkedQueue<>();
    private final CountDownLatch completionLatch;
    /** Bounds work in flight so a slow backend applies backpressure instead of queueing without limit. */
    private final Semaphore permits;
    private final AtomicInteger submitted = new AtomicInteger();

    private volatile long startedAtEpochMs;
    private volatile long finishedAtEpochMs;
    private volatile String status = "PENDING";
    private volatile String csvPath;

    LoadTestRun(String runId, Mode mode, int requested, int concurrency, boolean renderPerRequest) {
        this.runId = runId;
        this.mode = mode;
        this.requested = requested;
        this.concurrency = concurrency;
        this.renderPerRequest = renderPerRequest;
        this.completionLatch = new CountDownLatch(requested);
        this.permits = new Semaphore(concurrency);
    }

    void markStarted() {
        this.startedAtEpochMs = System.currentTimeMillis();
        this.status = "RUNNING";
    }

    void markFinished(String finalStatus) {
        this.finishedAtEpochMs = System.currentTimeMillis();
        this.status = finalStatus;
    }

    void setCsvPath(String csvPath) {
        this.csvPath = csvPath;
    }

    int nextRequestId() {
        return submitted.incrementAndGet();
    }

    /**
     * Records an outcome exactly once per request. Guards against Gotenberg's webhook
     * retries counting a single conversion twice.
     */
    void record(RequestRecord record) {
        records.add(record);
        completionLatch.countDown();
    }

    List<RequestRecord> recordsSnapshot() {
        return List.copyOf(records);
    }

    int inFlightCount() {
        return Math.max(0, submitted.get() - records.size());
    }

    long wallClockMs() {
        if (startedAtEpochMs == 0) {
            return 0L;
        }
        long end = finishedAtEpochMs > 0 ? finishedAtEpochMs : System.currentTimeMillis();
        return end - startedAtEpochMs;
    }

    public LoadTestSummary summary() {
        return LoadTestSummary.from(this);
    }
}
