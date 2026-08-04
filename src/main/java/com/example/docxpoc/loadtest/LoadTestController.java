package com.example.docxpoc.loadtest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.example.docxpoc.loadtest.LoadTestRun.Mode;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Control plane for Gotenberg load tests, plus the webhook endpoints Gotenberg calls
 * back on in async mode.
 *
 * <p><b>Do not expose these endpoints outside a local or dedicated test environment.</b>
 * They are unauthenticated and will happily saturate the backend on request.
 */
@Slf4j
@RestController
@RequestMapping("/api/loadtest")
@RequiredArgsConstructor
public class LoadTestController {

    private static final int DRAIN_BUFFER_BYTES = 16 * 1024;

    private final LoadTestService loadTestService;

    /** Uploads the DOCX template to use for subsequent runs. */
    @PostMapping("/template")
    public ResponseEntity<Map<String, Object>> uploadTemplate(@RequestParam("file") MultipartFile file)
            throws IOException {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "template file is empty"));
        }
        loadTestService.setTemplate(file.getBytes());
        return ResponseEntity.ok(Map.of(
                "filename", String.valueOf(file.getOriginalFilename()),
                "bytes", file.getSize()));
    }

    /**
     * Starts a run and returns immediately. Poll {@code GET /api/loadtest/runs/{runId}}
     * for progress.
     */
    @PostMapping("/run")
    public ResponseEntity<?> run(
            @RequestParam(defaultValue = "100") int count,
            @RequestParam(defaultValue = "sync") String mode,
            @RequestParam(defaultValue = "20") int concurrency,
            @RequestParam(defaultValue = "false") boolean renderPerRequest) {

        if (count < 1) {
            return ResponseEntity.badRequest().body(Map.of("error", "count must be at least 1"));
        }
        if (concurrency < 1) {
            return ResponseEntity.badRequest().body(Map.of("error", "concurrency must be at least 1"));
        }

        Mode parsedMode;
        try {
            parsedMode = Mode.valueOf(mode.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "mode must be one of: sync, webhook"));
        }

        try {
            LoadTestRun run = loadTestService.startRun(parsedMode, count, concurrency, renderPerRequest);
            return ResponseEntity.accepted().body(run.summary());
        } catch (LoadTestService.TemplatePreparationException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/runs/{runId}")
    public ResponseEntity<?> runStatus(@PathVariable String runId) {
        LoadTestRun run = loadTestService.getRun(runId);
        if (run == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(run.summary());
    }

    @GetMapping("/runs")
    public ResponseEntity<Map<String, Object>> listRuns() {
        return ResponseEntity.ok(Map.of("runIds", loadTestService.listRunIds()));
    }

    /**
     * Success callback for webhook mode. Gotenberg POSTs the finished PDF here.
     *
     * <p>The body is drained and counted rather than buffered: at 10,000 conversions,
     * retaining the PDFs would dominate heap usage and make the run measure GC pressure
     * instead of conversion throughput.
     */
    @PostMapping("/callback/{jobId}")
    public ResponseEntity<Void> callback(@PathVariable String jobId, HttpServletRequest request) {
        long bytes = 0L;
        String error = null;
        try {
            bytes = drainAndCount(request.getInputStream());
        } catch (IOException e) {
            error = "failed reading callback body: " + e.getMessage();
        }

        loadTestService.completeJob(jobId, HttpStatus.OK.value(), bytes, error);
        return ResponseEntity.ok().build();
    }

    /** Error callback for webhook mode. Gotenberg POSTs the failure detail here. */
    @PostMapping("/callback-error/{jobId}")
    public ResponseEntity<Void> callbackError(@PathVariable String jobId, HttpServletRequest request) {
        String detail;
        try (InputStream in = request.getInputStream()) {
            detail = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            detail = "unreadable error body: " + e.getMessage();
        }

        log.warn("Gotenberg reported a conversion failure for job {}: {}", jobId, detail);
        loadTestService.completeJob(jobId, HttpStatus.INTERNAL_SERVER_ERROR.value(), 0L,
                detail.isBlank() ? "gotenberg error callback" : detail);
        return ResponseEntity.ok().build();
    }

    private long drainAndCount(InputStream in) throws IOException {
        byte[] buffer = new byte[DRAIN_BUFFER_BYTES];
        long total = 0L;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
        }
        return total;
    }
}
