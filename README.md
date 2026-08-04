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

A separate stack and set of endpoints exist to measure bulk DOCX-to-PDF throughput in
both **sync** (blocking HTTP) and **webhook** (Gotenberg calls back with the finished
PDF) modes.

### 1. Start the scaled stack

```bash
docker compose -f docker-compose.loadtest.yml up --build

# or with more Gotenberg replicas
GOTENBERG_REPLICAS=8 docker compose -f docker-compose.loadtest.yml up --build
```

This runs N Gotenberg replicas behind an nginx round-robin proxy on port 3000, plus the
application with the `loadtest` profile active.

To iterate faster, run only the Gotenberg side in Docker and the app on the host:

```bash
docker compose -f docker-compose.loadtest.yml up -d gotenberg gotenberg-lb
mvn spring-boot:run -Dspring-boot.run.profiles=loadtest
```

### 2. Upload a template

```bash
curl -X POST http://localhost:8080/api/loadtest/template -F "file=@loadtest/smoke-template.docx"
```

`loadtest/smoke-template.docx` is a generated smoke template. It exercises the plain
text variables and a static table but **omits** the `{{repayments}}` loop tag and
`{{signatureSection}}`. Upload your real template for representative numbers — render
cost and PDF size both depend on it.

### 3. Run

```bash
# sync: blocking HTTP call per conversion
curl -X POST "http://localhost:8080/api/loadtest/run?count=100&mode=sync&concurrency=20"

# webhook: Gotenberg POSTs each finished PDF back to the app
curl -X POST "http://localhost:8080/api/loadtest/run?count=100&mode=webhook&concurrency=20"

# 10,000 documents
curl -X POST "http://localhost:8080/api/loadtest/run?count=10000&mode=sync&concurrency=40"
```

| Parameter | Default | Meaning |
|---|---|---|
| `count` | 100 | Number of conversions |
| `mode` | `sync` | `sync` or `webhook` |
| `concurrency` | 20 | Maximum conversions in flight |
| `renderPerRequest` | `false` | `false` renders the DOCX once and reuses it, isolating Gotenberg. `true` re-renders per request for the full poi-tl + Gotenberg figure. |

The run starts in the background and returns a `runId`. Only one run is allowed at a
time — overlapping runs would contend for the same Gotenberg workers.

### 4. Read results

```bash
curl http://localhost:8080/api/loadtest/runs/{runId}    # live or final summary
curl http://localhost:8080/api/loadtest/runs            # all run ids
```

Per-request rows are written to `loadtest-results/{runId}.csv`
(`requestId,jobId,mode,submittedAtEpochMs,completedAtEpochMs,latencyMs,httpStatus,pdfBytes,error`).
The summary reports throughput and min/p50/p95/p99/max latency. Generated PDFs are
counted and discarded, never retained — at 10,000 conversions, keeping them would make
the run measure GC pressure instead of conversion throughput.

### Measured baseline

100 documents at `concurrency=20`, smoke template, 10-CPU Docker VM:

| Setup | Wall clock | Throughput | p50 | p95 |
|---|---|---|---|---|
| sync, 1 replica | 13.15s | 7.6/s | 2494ms | 2837ms |
| sync, 4 replicas | 4.55s | 22.0/s | 758ms | 1496ms |
| webhook, 4 replicas | 4.28s | 23.4/s | 705ms | 1280ms |

Notes on interpreting these:

- **Scaling is sub-linear.** 4x the replicas gave 2.9x the throughput. Past roughly one
  replica per 2 host CPUs, replicas just contend for the same cores.
- **A single Gotenberg container is not serialised.** It managed 7.6 conversions/s, so
  it processes conversions concurrently despite there being no
  `--libreoffice-max-concurrency` flag.
- **Discard the first run.** A cold LibreOffice measured 14.2/s where a warm one
  measured 23.4/s on identical settings.
- Extrapolating 22/s, 10,000 documents takes roughly 8 minutes on this hardware.

### Gotenberg flags that matter under load

Set in `docker-compose.loadtest.yml`; the defaults will distort results:

| Flag | Default | Load test value | Why |
|---|---|---|---|
| `--libreoffice-restart-after` | 10 | 0 | Restarting LibreOffice every 10 conversions otherwise dominates the measurement |
| `--api-timeout` | 30s | 300s | Queued conversions blow through 30s and report as failures rather than backpressure |
| `--webhook-max-retry` | 4 | 1 | Retries would deliver the same PDF several times and inflate the count |

### Troubleshooting

- **Webhook run completes with `no webhook callback within Ns` errors** — Gotenberg
  cannot reach the callback URL. `loadtest.webhook.base-url` is resolved *inside the
  Gotenberg container*: use `http://docx-service:8080` when the app runs in Compose, and
  `http://host.docker.internal:8080` when it runs on the host.
- **Latency rises but throughput is flat** — the backend is saturated. Raise replicas,
  not concurrency.
- **Many failures at high `count`** — check `ulimit -n`; the connection pool needs file
  descriptors.

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
- Apache POI 5.2.5 (OOXML support)
- Gotenberg 8 (LibreOffice-based PDF conversion microservice)
- Lombok

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
