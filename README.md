# Signature DOCX POC

A Spring Boot application demonstrating dynamic variable replacement in DOCX files using poi-tl and PDF conversion using Gotenberg.

## Features

- Replaces variables in DOCX files using poi-tl template engine (format: `{{variableName}}`)
- Converts the processed DOCX to PDF using Gotenberg (LibreOffice-based microservice)
- Single endpoint for streamlined processing
- Runs in Docker with Gotenberg service

## Prerequisites

### For Docker (Recommended)
- Docker
- Docker Compose

### For Local Development
- Java 17 or higher
- Maven 3.6+
- Docker (to run Gotenberg service)

## Getting Started

### Option 1: Using Docker Compose (Recommended)

1. **Build and run with Docker Compose:**
   ```bash
   docker-compose up --build
   ```

   This will start:
   - Gotenberg service on `http://localhost:3000`
   - Spring Boot application on `http://localhost:8080`

2. **To run in detached mode:**
   ```bash
   docker-compose up -d
   ```

3. **To stop:**
   ```bash
   docker-compose down
   ```

### Option 2: Local Development

1. **Start Gotenberg service:**
   ```bash
   docker run -d -p 3000:3000 --name gotenberg gotenberg/gotenberg:8
   ```

2. **Install dependencies:**
   ```bash
   mvn clean install
   ```

3. **Run the application:**
   ```bash
   mvn spring-boot:run
   ```

   The application will start on `http://localhost:8080`

4. **Stop Gotenberg when done:**
   ```bash
   docker stop gotenberg && docker rm gotenberg
   ```

## API Endpoints

### Process DOCX Document
Replaces variables in the uploaded DOCX file and returns a PDF.

**Endpoint:** `POST /api/documents/process`

**Example:**
```bash
curl -X POST http://localhost:8080/api/documents/process \
  -F "file=@template.docx" \
  -o output.pdf
```

**Current hardcoded variables:**
- `{{testVariable}}` → "I am replaced"
- `{{termLoan}}` → "150,000"
- `{{totalAmount}}` → "200,000"
- `{{totalRepaymentAmount}}` → "12,000"
- `{{repayments}}` → Table of 12 monthly repayment rows (using `LoopRowTableRenderPolicy`)
- `{{signatureSection}}` → 2 signature blocks, each with an image, name, date, designation, and NRIC

## Load Testing Gotenberg

Measures bulk DOCX-to-PDF throughput in **sync** (blocking HTTP) and **webhook** (Gotenberg
POSTs the finished PDF back) modes, against N Gotenberg replicas behind an nginx round-robin
proxy.

### How to run

```bash
# 1. Start the stack (8 Gotenberg replicas + nginx on :3000 + app on :8080)
docker compose -f docker-compose.loadtest.yml up -d --build

# 2. Upload the template (cached in memory, reused for every conversion)
curl -X POST http://localhost:8080/api/loadtest/template -F "file=@loadtest/my-template.docx"

# 3. Warm the backend and throw the result away
curl -X POST "http://localhost:8080/api/loadtest/run?count=100&mode=sync&concurrency=16"

# 4. Real run
curl -X POST "http://localhost:8080/api/loadtest/run?count=10000&mode=sync&concurrency=16"

# 5. Poll for progress, or read the CSV when it finishes
curl http://localhost:8080/api/loadtest/runs/{runId}
```

To iterate faster, run only the Gotenberg side in Docker and the app on the host:

```bash
docker compose -f docker-compose.loadtest.yml up -d gotenberg gotenberg-lb
mvn spring-boot:run -Dspring-boot.run.profiles=loadtest
```

### Parameters

`POST /api/loadtest/run`

| Parameter | Default | Meaning |
|---|---|---|
| `count` | 100 | Number of conversions |
| `mode` | `sync` | `sync` or `webhook` |
| `concurrency` | 20 | Maximum conversions in flight |
| `renderPerRequest` | `false` | `false` renders the DOCX once and reuses it, isolating Gotenberg. `true` re-renders per request for the full poi-tl + Gotenberg figure. |

Only one run is allowed at a time. Runs start in the background and return a `runId`.

| Endpoint | Purpose |
|---|---|
| `POST /api/loadtest/template` | Upload the DOCX template |
| `POST /api/loadtest/run` | Start a run |
| `GET /api/loadtest/runs/{runId}` | Live or final summary |
| `GET /api/loadtest/runs` | All run ids |

Environment overrides:

```bash
GOTENBERG_REPLICAS=12 \
GOTENBERG_RESTART_AFTER=0 \
GOTENBERG_LOG_LEVEL=info \
GOTENBERG_URL=http://gotenberg:3000 \
  docker compose -f docker-compose.loadtest.yml up -d --build
```

> The `/api/loadtest/*` endpoints are unauthenticated and will saturate the backend on
> request. Do not expose them outside a local or dedicated test environment.

### Output

Summary JSON reports throughput and min/p50/p95/p99/max latency. Per-request rows go to
`loadtest-results/{runId}.csv`:

```
requestId,jobId,mode,submittedAtEpochMs,completedAtEpochMs,latencyMs,httpStatus,pdfBytes,error
```

PDFs are counted and discarded, never retained.

## Load Test Results

Measured on a 10-CPU Docker Desktop VM on macOS, `restart-after=10`, real Letter of Offer
template (530KB DOCX, 20-page 340KB PDF), `renderPerRequest=false`.

> Repeat runs of an identical config varied by up to 20%, and one outlier run came in at 40%
> of the mean. Treat these as relative comparisons, not capacity figures. Re-measure on a
> dedicated Linux host before planning against them.

### Best configuration

**8 replicas, `concurrency=16` → 7.70/s.** 10,000 documents in **~22 minutes**, ~3.4GB of PDF.

### Replicas

80 documents per run, two runs per cell, mean reported.

| replicas | conc | Throughput | Individual runs | p50 | Gotenberg CPU | vs 1 replica |
|---|---|---|---|---|---|---|
| 1 | 8 | 2.06/s | 2.00 / 2.13 | 3761ms | **103%** | 1.0x |
| 1 | 16 | 1.67/s | 1.70 / 1.64 | 8456ms | 103% | 0.8x |
| 2 | 8 | 2.14/s | 0.82 / 3.46 | 2921ms | 231% | 1.0x |
| 2 | 16 | 3.80/s | 4.06 / 3.55 | 3376ms | 212% | 1.8x |
| 4 | 8 | 6.24/s | 6.42 / 6.06 | 1037ms | 354% | 3.0x |
| 4 | 16 | 5.90/s | 5.68 / 6.12 | 1846ms | 415% | 2.9x |
| 8 | 8 | 6.99/s | 7.59 / 6.39 | 835ms | 550% | 3.4x |
| **8** | **16** | **7.70/s** | 8.16 / 7.24 | 1428ms | 496% | **3.7x** |
| 12 | 8 | 6.96/s | 6.99 / 6.94 | 877ms | 664% | 3.4x |
| 12 | 16 | 6.27/s | 6.06 / 6.48 | 1390ms | 747% | 3.0x |

- **One container converts one document at a time.** At 1 replica CPU pins to ~103%, exactly
  one core. Replicas are the only way to convert in parallel, which is why LibreOffice has no
  `--libreoffice-max-concurrency` flag.
- **8 replicas is the peak on 10 CPUs; 12 regresses.** Gains fall off after 4 (3.0x), reach
  3.7x at 8, then drop to 3.4x at 12 while CPU climbs to 747%.
- **Scale concurrency with replicas, roughly 2x.** 1 replica is fastest at `concurrency=8`;
  8 replicas at `concurrency=16`. Too little leaves replicas idle, too much only queues —
  `concurrency=16` on a single replica was *slower* than 8 (1.67 vs 2.06/s).

### Concurrency beyond the optimum

8 replicas, earlier font-embedded template:

| concurrency | Throughput | p50 | p95 | Gotenberg CPU |
|---|---|---|---|---|
| 8 | 3.87/s | 1577ms | 5511ms | 346% |
| 24 | 3.73/s | 4243ms | 15697ms | 914% |
| 48 | **2.61/s** | 15791ms | 26309ms | 924% |

Past the useful point, extra load buys latency, not work.

### Per-conversion cost

Sequential (`concurrency=1`) on a warmed backend, so no queueing.

| Template | DOCX | PDF | p50 per conversion |
|---|---|---|---|
| `smoke-template.docx` | 2.5KB | 1 page, 22KB | 128ms |
| Letter of Offer, 8 embedded fonts | 4.1MB | 20 pages, 320KB | 502ms |
| Letter of Offer, fonts removed | 530KB | 20 pages, 340KB | **448ms** |

- The real template costs ~3.5x the toy one, dominated by laying out 20 pages rather than 1.
- **Embedded fonts barely matter.** Stripping all eight shrank the DOCX 8x but improved
  per-conversion time only ~11%, and the PDF got slightly *larger* (LibreOffice embeds its own
  subsets instead). Content was identical: 20 pages, 54,342 characters either way.

### LibreOffice restarts

`--libreoffice-restart-after`, three runs of 100 documents, 4 replicas, `concurrency=8`.

| Setting | Mean | Range |
|---|---|---|
| `10` (Gotenberg default) | **6.51/s** | 6.27 - 6.90 |
| `0` (disabled) | 5.29/s | 4.14 - 6.84 |

Disabling restarts was slower on average and far less consistent. Keep the default.

### nginx vs Docker DNS

100 conversions across 4 replicas, counted from each container's request log.

| `GOTENBERG_URL` | Conversions per replica | Throughput |
|---|---|---|
| `http://gotenberg:3000` (Docker DNS) | **100 / 0 / 0 / 0** | 2.00/s |
| `http://gotenberg-lb:3000` (nginx) | 28 / 36 / 14 / 22 | 4.61 - 5.38/s |

Docker DNS returns all replica addresses, but the pooled HttpClient resolves once and reuses
that keep-alive connection, so every conversion lands on one replica. `nginx.conf` puts the
upstream in a variable to force a per-request resolver lookup; a plain `upstream` block would
pin just as badly.

### Sync vs webhook

Equivalent throughput (5.58 vs 5.66/s at 4 replicas, within noise). Webhook's benefit is not
speed — the caller does not hold a thread and socket open per conversion.

### Gotenberg flags

| Flag | Default | Used | Why |
|---|---|---|---|
| `--api-timeout` | 30s | 300s | Queued conversions blow through 30s and report as failures rather than backpressure |
| `--webhook-max-retry` | 4 | 1 | Retries would deliver the same PDF several times and inflate the count |
| `--libreoffice-restart-after` | 10 | 10 | Measured faster and more consistent than disabling |

### Gotchas

- **Discard the first run.** Cold LibreOffice measured 14.2/s where warm measured 23.4/s on
  identical settings. There is no warm-up phase.
- **Do not use `file` to count PDF pages.** It reports 181 for these 20-page outputs because it
  greps the first `/Count` it finds. Use a PDF library or count `/Type /Page` objects.
- **Webhook run reports `no webhook callback within Ns`** — Gotenberg cannot reach the callback.
  `loadtest.webhook.base-url` resolves *inside the Gotenberg container*: `http://docx-service:8080`
  in Compose, `http://host.docker.internal:8080` when the app runs on the host.
- **Many failures at high `count`** — check `ulimit -n`; the connection pool needs file descriptors.

## Creating a Template DOCX

Create a DOCX file with variables in the format `{{variableName}}`. For example:

```
This is a test document.

The test variable is: {{testVariable}}
Term Loan: {{termLoan}}
Total Amount: {{totalAmount}}
Total Repayment Amount: {{totalRepaymentAmount}}

These will be replaced automatically.
```

### Table Loops (Repayments)

To render repeating table rows, create a table in your DOCX with a row containing `{{repayments}}` as a tag. poi-tl's `LoopRowTableRenderPolicy` will duplicate the row for each entry. Use field names from the `Repayments` entity:

| Month | Amount |
|-------|--------|
| {{month}} | {{amount}} |

### Signature Sections

The `{{signatureSection}}` variable renders signature blocks, each containing:
- `{{signature}}` - an embedded signature image (PNG)
- `{{name}}` - signer's name
- `{{date}}` - date of signing
- `{{designation}}` - signer's designation
- `{{nric}}` - signer's NRIC

A sample signature image is provided at `src/main/resources/signature.png`.

See `src/main/resources/templates/README.md` for additional guidance on creating templates.

## Project Structure

```
signature-docx-poc/
├── src/
│   └── main/
│       ├── java/
│       │   └── com/example/docxpoc/
│       │       ├── DocxPocApplication.java
│       │       ├── controller/
│       │       │   └── DocumentController.java
│       │       ├── entity/
│       │       │   ├── Repayments.java
│       │       │   └── Signature.java
│       │       └── service/
│       │           └── DocumentService.java
│       └── resources/
│           ├── application.properties
│           ├── application-docker.properties
│           ├── signature.png
│           └── templates/
│               └── README.md
├── loadtest/
│   ├── nginx.conf              # round-robin proxy over the Gotenberg replicas
│   └── smoke-template.docx     # generated template for smoke runs
├── docker-compose.yml
├── docker-compose.loadtest.yml
├── Dockerfile
└── pom.xml
```

Load test sources live in `src/main/java/com/example/docxpoc/loadtest/`
(`LoadTestController`, `LoadTestService`, `LoadTestRun`, `LoadTestSummary`,
`RequestRecord`), with the pooled HTTP client in `config/HttpClientConfig.java` and the
Gotenberg calls in `service/GotenbergClient.java`.

## Dependencies

- Spring Boot 3.2.1
- Apache HttpClient 5 (pooled Gotenberg client)
- poi-tl 1.12.2 (DOCX template engine)
- Apache POI 5.5.1 (OOXML support, upgraded independently of poi-tl — see below)
- Gotenberg 8 (LibreOffice-based PDF conversion microservice)
- Lombok

## Upgrading Apache POI independently of poi-tl

poi-tl has not had a stable release since 1.12.2 (Mar 2024) and still declares Apache
POI 5.2.2. POI is therefore pinned directly via the `poi.version` property in `pom.xml`,
which overrides the transitive version, so POI can be kept current without waiting on a
poi-tl release.

**This is not covered by the compiler.** The application calls poi-tl only and never
Apache POI directly, so an incompatible POI upgrade still compiles cleanly and fails at
runtime inside poi-tl, typically as a `NoSuchMethodError`. `PoiTlRenderTest` exists to
catch that: it renders the real template through `DocumentService` and asserts on the
result. Run `mvn test` after any POI bump.

### Verified upgrade path

Stepped 5.2.5 → 5.3.0 → 5.4.1 → 5.5.1, one commit per hop, tests green at every step.

| POI | xmlbeans | commons-compress | commons-io | commons-collections4 |
|---|---|---|---|---|
| 5.2.5 | 5.2.0 | 1.25.0 | 2.15.0 | 4.4 |
| 5.3.0 | 5.2.1 | 1.26.2 | 2.16.1 | 4.4 |
| 5.4.1 | 5.3.0 | 1.27.1 | 2.18.0 | 4.4 |
| 5.5.1 | 5.3.0 | 1.28.0 | 2.21.0 | 4.5.0 |

POI 5.3.0 raises its baseline to Java 11; this project targets 17. POI logs through
`log4j-api`, already bridged to SLF4J by Spring Boot's managed `log4j-to-slf4j`.

### Output equivalence

The generated PDF is unchanged. Converting the same template on 5.2.5 and 5.5.1 produced
PDFs of identical size (339,632 bytes, 181 pages); decompressing every PDF stream and
comparing showed the only difference to be the XMP creation timestamp.

The rendered DOCX differs in exactly one respect: POI 5.2.5 emitted two empty `<w:u/>`
elements in the signature block that 5.5.1 omits. These carry no `w:val`, are absent from
the source template, and have no effect on the rendered PDF.

A 300-conversion load run with `renderPerRequest=true` completed 300/300 with no failures
and no POI-related errors across 336 renders. Throughput is not compared here: the two
builds were exercised in different environments (container vs host JVM) and Gotenberg
dominates end-to-end latency, so the numbers are not attributable to POI.

## Notes

- Variables use poi-tl's double curly brace syntax: `{{variableName}}`
- Maximum file upload size: 10MB
- Supported input format: .docx (Microsoft Word 2007+)
- Output format: PDF
- PDF conversion is handled by Gotenberg (LibreOffice) via HTTP API

## Troubleshooting

If you encounter PDF conversion issues, ensure:
1. The DOCX file is not corrupted
2. The Gotenberg service is running and accessible
3. Font files are available on the system
4. Sufficient memory is allocated to the JVM
