package com.example.docxpoc.loadtest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import com.example.docxpoc.loadtest.LoadTestRun.Mode;
import com.example.docxpoc.service.DocumentService;
import com.example.docxpoc.service.GotenbergClient;
import com.example.docxpoc.service.SampleDataFactory;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Drives bulk DOCX-to-PDF conversions against Gotenberg and records per-request timings.
 *
 * <p>Only one run is permitted at a time; overlapping runs would contend for the same
 * Gotenberg workers and make both sets of numbers meaningless.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoadTestService {

    private static final String CLASSPATH_TEMPLATE = "templates/loadtest-template.docx";
    private static final DateTimeFormatter RUN_ID_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final DocumentService documentService;
    private final GotenbergClient gotenbergClient;
    private final SampleDataFactory sampleDataFactory;

    /** Base URL Gotenberg should call back on. Must be reachable *from the Gotenberg container*. */
    @Value("${loadtest.webhook.base-url:http://host.docker.internal:8080}")
    private String webhookBaseUrl;

    @Value("${loadtest.results-dir:./loadtest-results}")
    private String resultsDir;

    /** How long to wait for a webhook callback before writing the request off as lost. */
    @Value("${loadtest.job-timeout-seconds:600}")
    private long jobTimeoutSeconds;

    /** Hard stop for a whole run, so a wedged backend cannot leave the run hanging forever. */
    @Value("${loadtest.run-timeout-seconds:7200}")
    private long runTimeoutSeconds;

    private final Map<String, LoadTestRun> runs = new ConcurrentHashMap<>();
    private final Map<String, InFlightJob> inFlightJobs = new ConcurrentHashMap<>();
    private final AtomicReference<byte[]> uploadedTemplate = new AtomicReference<>();
    private final AtomicBoolean runInProgress = new AtomicBoolean(false);

    private ScheduledExecutorService timeoutSweeper;

    private record InFlightJob(String runId, int requestId, long submittedAtEpochMs) {
    }

    /**
     * The template is missing or is not a readable DOCX. Distinct from the
     * run-already-in-progress conflict so the two map to different HTTP statuses.
     */
    public static class TemplatePreparationException extends RuntimeException {
        TemplatePreparationException(String message, Throwable cause) {
            super(message, cause);
        }

        TemplatePreparationException(String message) {
            super(message);
        }
    }

    @PostConstruct
    void startTimeoutSweeper() {
        timeoutSweeper = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "loadtest-timeout-sweeper");
            thread.setDaemon(true);
            return thread;
        });
        timeoutSweeper.scheduleWithFixedDelay(this::expireStaleJobs, 5, 5, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stopTimeoutSweeper() {
        if (timeoutSweeper != null) {
            timeoutSweeper.shutdownNow();
        }
    }

    /** Caches an uploaded template for subsequent runs. */
    public void setTemplate(byte[] templateBytes) {
        uploadedTemplate.set(templateBytes);
        log.info("Load test template cached ({} bytes)", templateBytes.length);
    }

    public LoadTestRun getRun(String runId) {
        return runs.get(runId);
    }

    public List<String> listRunIds() {
        return new ArrayList<>(runs.keySet());
    }

    /**
     * Starts a run in the background.
     *
     * @return the created run, whose id is used to poll for progress
     * @throws IllegalStateException if a run is already active or no template is available
     */
    public LoadTestRun startRun(Mode mode, int count, int concurrency, boolean renderPerRequest) throws IOException {
        byte[] templateBytes = resolveTemplate();

        if (!runInProgress.compareAndSet(false, true)) {
            throw new IllegalStateException("A load test run is already in progress");
        }

        LoadTestRun run;
        byte[] prerendered;
        try {
            String runId = mode.name().toLowerCase() + "-" + LocalDateTime.now().format(RUN_ID_FORMAT);
            run = new LoadTestRun(runId, mode, count, concurrency, renderPerRequest);
            runs.put(runId, run);

            // Rendering once up front keeps the measurement focused on Gotenberg. Callers
            // who want the end-to-end figure pass renderPerRequest=true instead.
            prerendered = renderPerRequest ? null : render(templateBytes);
        } catch (Exception e) {
            // Nothing has started yet, so release the guard here rather than leaving the
            // service permanently wedged against every future run.
            runInProgress.set(false);
            throw new TemplatePreparationException("Failed to prepare load test run: " + rootMessage(e), e);
        }

        Thread driver = new Thread(() -> {
            try {
                execute(run, templateBytes, prerendered);
            } catch (Exception e) {
                log.error("Load test run {} failed", run.getRunId(), e);
                run.markFinished("FAILED");
            } finally {
                runInProgress.set(false);
            }
        }, "loadtest-driver-" + run.getRunId());
        driver.setDaemon(true);
        driver.start();

        return run;
    }

    private void execute(LoadTestRun run, byte[] templateBytes, byte[] prerendered) throws InterruptedException {
        log.info("Starting load test run {}: mode={} count={} concurrency={} renderPerRequest={}",
                run.getRunId(), run.getMode(), run.getRequested(), run.getConcurrency(), run.isRenderPerRequest());

        run.markStarted();
        ExecutorService pool = Executors.newFixedThreadPool(run.getConcurrency());

        try {
            for (int i = 0; i < run.getRequested(); i++) {
                // Bounds work in flight. In sync mode the pool size would already do this,
                // but acquiring here keeps both modes on one code path.
                run.getPermits().acquire();
                int requestId = run.nextRequestId();
                pool.submit(() -> dispatch(run, templateBytes, prerendered, requestId));
            }

            boolean finished = run.getCompletionLatch().await(runTimeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                log.warn("Run {} hit the {}s run timeout with {} still in flight",
                        run.getRunId(), runTimeoutSeconds, run.inFlightCount());
                failRemainingJobs(run, "run timeout reached");
            }
            run.markFinished(finished ? "COMPLETED" : "TIMED_OUT");
        } finally {
            pool.shutdownNow();
        }

        writeCsv(run);
        logSummary(run.summary());
    }

    private void dispatch(LoadTestRun run, byte[] templateBytes, byte[] prerendered, int requestId) {
        String jobId = run.getRunId() + "-" + requestId;
        long submittedAt = System.currentTimeMillis();

        try {
            byte[] docx = prerendered != null ? prerendered : render(templateBytes);

            if (run.getMode() == Mode.SYNC) {
                GotenbergClient.ConversionResult result = gotenbergClient.convertSync(docx, "document.docx");
                long completedAt = System.currentTimeMillis();
                run.record(new RequestRecord(requestId, jobId, "sync", submittedAt, completedAt,
                        completedAt - submittedAt, result.httpStatus(), result.pdfBytes(), null));
                run.getPermits().release();
            } else {
                // Register before submitting: Gotenberg can call back before the POST returns.
                inFlightJobs.put(jobId, new InFlightJob(run.getRunId(), requestId, submittedAt));
                try {
                    gotenbergClient.submitWebhook(docx, "document.docx",
                            webhookBaseUrl + "/api/loadtest/callback/" + jobId,
                            webhookBaseUrl + "/api/loadtest/callback-error/" + jobId,
                            jobId);
                } catch (Exception e) {
                    completeJob(jobId, 0, 0L, "submit failed: " + rootMessage(e));
                }
            }
        } catch (Exception e) {
            long completedAt = System.currentTimeMillis();
            if (run.getMode() == Mode.SYNC) {
                run.record(new RequestRecord(requestId, jobId, "sync", submittedAt, completedAt,
                        completedAt - submittedAt, 0, 0L, rootMessage(e)));
                run.getPermits().release();
            } else {
                completeJob(jobId, 0, 0L, rootMessage(e));
            }
        }
    }

    /**
     * Completes a webhook job. Returns false for an unknown job id, which is the normal
     * outcome for a Gotenberg webhook retry of an already-recorded conversion.
     */
    public boolean completeJob(String jobId, int httpStatus, long pdfBytes, String error) {
        InFlightJob job = inFlightJobs.remove(jobId);
        if (job == null) {
            log.debug("Ignoring callback for unknown or already-completed job {}", jobId);
            return false;
        }

        LoadTestRun run = runs.get(job.runId());
        if (run == null) {
            return false;
        }

        long completedAt = System.currentTimeMillis();
        run.record(new RequestRecord(job.requestId(), jobId, "webhook", job.submittedAtEpochMs(), completedAt,
                completedAt - job.submittedAtEpochMs(), httpStatus, pdfBytes, error));
        run.getPermits().release();
        return true;
    }

    /** Writes off webhook jobs whose callback never arrived, so a run can always finish. */
    private void expireStaleJobs() {
        long cutoff = System.currentTimeMillis() - Duration.ofSeconds(jobTimeoutSeconds).toMillis();
        inFlightJobs.entrySet().stream()
                .filter(entry -> entry.getValue().submittedAtEpochMs() < cutoff)
                .map(Map.Entry::getKey)
                .toList()
                .forEach(jobId -> {
                    if (completeJob(jobId, 0, 0L, "no webhook callback within " + jobTimeoutSeconds + "s")) {
                        log.warn("Job {} timed out waiting for a webhook callback", jobId);
                    }
                });
    }

    private void failRemainingJobs(LoadTestRun run, String reason) {
        inFlightJobs.entrySet().stream()
                .filter(entry -> entry.getValue().runId().equals(run.getRunId()))
                .map(Map.Entry::getKey)
                .toList()
                .forEach(jobId -> completeJob(jobId, 0, 0L, reason));

        // Sync-mode requests are not tracked in inFlightJobs, so drain the latch directly.
        while (run.getCompletionLatch().getCount() > 0) {
            run.record(new RequestRecord(-1, null, run.getMode().name().toLowerCase(),
                    0L, 0L, 0L, 0, 0L, reason));
        }
    }

    private byte[] render(byte[] templateBytes) throws Exception {
        return documentService
                .replaceVariablesInDocx(new ByteArrayInputStream(templateBytes), sampleDataFactory.buildVariables())
                .toByteArray();
    }

    /**
     * Resolves the template bytes once per run: the uploaded template if present,
     * otherwise the classpath fallback.
     *
     * <p>Read once and reused for every conversion, so neither the upload nor this read
     * appears in the measurement.
     */
    private byte[] resolveTemplate() throws IOException {
        byte[] uploaded = uploadedTemplate.get();
        if (uploaded != null) {
            return uploaded;
        }

        ClassPathResource resource = new ClassPathResource(CLASSPATH_TEMPLATE);
        if (!resource.exists()) {
            throw new TemplatePreparationException("No template available. POST one to /api/loadtest/template, "
                    + "or place it on the classpath at " + CLASSPATH_TEMPLATE);
        }
        try (InputStream in = resource.getInputStream()) {
            return in.readAllBytes();
        }
    }

    private void writeCsv(LoadTestRun run) {
        try {
            Path directory = Path.of(resultsDir);
            Files.createDirectories(directory);
            Path csv = directory.resolve(run.getRunId() + ".csv");

            List<String> lines = new ArrayList<>();
            lines.add(RequestRecord.csvHeader());
            run.recordsSnapshot().stream()
                    .sorted((left, right) -> Integer.compare(left.requestId(), right.requestId()))
                    .map(RequestRecord::toCsvRow)
                    .forEach(lines::add);

            Files.write(csv, lines, StandardCharsets.UTF_8);
            run.setCsvPath(csv.toAbsolutePath().toString());
            log.info("Wrote {} rows to {}", lines.size() - 1, csv.toAbsolutePath());
        } catch (IOException e) {
            log.error("Failed to write results CSV for run {}", run.getRunId(), e);
        }
    }

    private void logSummary(LoadTestSummary summary) {
        log.info("""
                Load test {} finished [{}]
                  mode={} requested={} succeeded={} failed={} concurrency={} renderPerRequest={}
                  wallClock={}s throughput={} conversions/s
                  latency ms: min={} p50={} p95={} p99={} max={} mean={}
                  totalPdf={}MB csv={}""",
                summary.runId(), summary.status(),
                summary.mode(), summary.requested(), summary.succeeded(), summary.failed(),
                summary.concurrency(), summary.renderPerRequest(),
                summary.wallClockMs() / 1000.0, summary.throughputPerSecond(),
                summary.latencyMinMs(), summary.latencyP50Ms(), summary.latencyP95Ms(),
                summary.latencyP99Ms(), summary.latencyMaxMs(), summary.latencyMeanMs(),
                summary.totalPdfMegabytes(), summary.csvPath());

        if (!summary.sampleErrors().isEmpty()) {
            log.warn("Sample errors from run {}: {}", summary.runId(), summary.sampleErrors());
        }
    }

    private static String rootMessage(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        return cursor.getClass().getSimpleName() + ": " + cursor.getMessage();
    }
}
