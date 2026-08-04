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
curl -X POST http://localhost:8080/api/loadtest/template -F "file=@loadtest/my-template.docx"
```

The upload happens **once** and the bytes are cached and reused for every conversion, so
it does not appear in the measurement. Re-upload after an app restart. If nothing is
uploaded, the harness falls back to the classpath at
`templates/loadtest-template.docx`.

Use your real template — render cost and PDF size dominate the results (see
[Measured baseline](#measured-baseline)). Real templates are gitignored; keep them out of
the repo.

`loadtest/smoke-template.docx` is a tracked, generated fallback so the harness runs out
of the box. It exercises the plain text variables and a static table but **omits** the
`{{repayments}}` loop tag and `{{signatureSection}}`, and its 1-page output is roughly
4x faster to convert than a real Letter of Offer. Do not plan capacity from it.

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

> The `/api/loadtest/*` endpoints are unauthenticated and will saturate the backend on
> request. Do not expose them outside a local or dedicated test environment.

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

### Why nginx? Doesn't Docker round-robin already?

Docker's embedded DNS *does* return every replica address and rotate the order —
`getent hosts gotenberg` inside the network returns all four IPs. But that only helps a
client that re-resolves on every request. This app uses a pooled Apache HttpClient with
keep-alive: it resolves `gotenberg` once, connects to the first address returned, and then
reuses that connection. The JVM's own 30-second DNS cache compounds it. The result is that
every conversion lands on one replica.

Measured, 100 conversions across 4 replicas, counted from each container's request log:

| `GOTENBERG_URL` | Conversions per replica | Throughput |
|---|---|---|
| `http://gotenberg:3000` (Docker DNS) | **100 / 0 / 0 / 0** | 2.00/s |
| `http://gotenberg-lb:3000` (nginx) | 28 / 36 / 14 / 22 | 4.61 - 5.38/s |

Docker DNS mode matched a single container exactly (1.93-2.02/s measured directly), which
is the giveaway: the other three replicas sat idle the whole run.

nginx avoids this because `loadtest/nginx.conf` puts the upstream in a *variable*
(`set $gotenberg gotenberg:3000; proxy_pass http://$gotenberg;`), which forces a fresh
resolver lookup per request. An ordinary `upstream` block would resolve once at startup and
pin just as badly. Distribution is still not perfectly even (14-36) because it depends on
Docker's DNS record ordering, but every replica does work.

Verify it yourself:

```bash
GOTENBERG_LOG_LEVEL=info docker compose -f docker-compose.loadtest.yml up -d --build
# ...run a load test, then:
for c in $(docker compose -f docker-compose.loadtest.yml ps -q gotenberg); do
  echo "$c: $(docker logs $c 2>&1 | grep -c 'forms/libreoffice/convert')"
done
```

The alternatives to nginx would be disabling keep-alive, setting
`networkaddress.cache.ttl=0`, or resolving all replica IPs in Java and round-robining
client-side. All of them are more code and slower per request than one proxy hop.

### Measured baseline

Measured on a 10-CPU Docker Desktop VM on macOS, with the app running on the host.
`renderPerRequest=false` throughout.

> These absolute numbers are host-limited and noisy — repeat runs of an identical config
> varied between 3.16/s and 5.75/s. Treat them as relative comparisons, not capacity
> figures. Run on a dedicated Linux host with a known CPU allocation before planning
> against them.

#### Per-conversion cost

Sequential (`concurrency=1`), direct to a single warmed container, so no queueing:

| Template | DOCX | PDF | p50 per conversion | Sequential rate |
|---|---|---|---|---|
| `smoke-template.docx` | 2.5KB | 1 page, 22KB | 128ms | 6.91/s |
| Real Letter of Offer | 4.1MB | 20 pages, 320KB | **502ms** | 1.75/s |

The real template costs **3.9x more per conversion**. Its DOCX is large because it embeds
eight fonts (~5.4MB of `.odttf`), which LibreOffice must load and subset on every
conversion, on top of laying out 20 pages instead of 1. This is the single biggest factor
in the results.

#### Throughput vs replicas and concurrency (real template)

| Setup | concurrency | Throughput | p50 | p95 | Gotenberg CPU |
|---|---|---|---|---|---|
| 1 container, no nginx | 8 | 1.93 - 2.02/s | ~4000ms | — | — |
| nginx + 2 replicas | 8 | 3.19/s | 2132ms | 3956ms | 216% |
| nginx + 4 replicas | 8 | 3.05 - 5.29/s | ~2200ms | 5594ms | 329% |
| nginx + 8 replicas | 8 | 3.87 - 5.75/s | 1577ms | 5511ms | 346% |
| nginx + 8 replicas | 24 | 3.73/s | 4243ms | 15697ms | **914%** |
| nginx + 8 replicas | 48 | **2.61/s** | 15791ms | 26309ms | **924%** |

#### Why it stops scaling

- **The host CPU is the wall, not Gotenberg.** A conversion needs ~0.5s of largely
  single-threaded LibreOffice work. On 10 CPUs the theoretical ceiling is ~20/s; the best
  observed was 5.75/s, so real efficiency is only ~30%. The rest goes to LibreOffice
  startup and IPC, handling a 4MB upload per request, and writing a 320KB PDF.
- **Past concurrency 8, more load makes it slower.** Going 8 -> 24 -> 48 pushed CPU from
  346% to ~920% of the 1000% available while throughput *fell* from 3.87 to 2.61/s and p50
  went from 1.6s to 15.8s. That is thrashing: the replicas contend for cores and burn CPU
  on context switching rather than conversions. Adding concurrency past this point only
  inflates latency.
- **Replicas help, but sub-linearly and only up to the core count.** 1 -> 8 replicas gave
  roughly 2-3x, not 8x. Past about one replica per host CPU there is nothing left to win.
- **Sync and webhook throughput are equivalent** (5.58 vs 5.66/s at 4 replicas, within
  noise). Webhook's benefit is not speed — it is that the caller does not hold a thread and
  socket open for the duration of each conversion.
- **Discard the first run.** A cold LibreOffice measured 14.2/s where a warm one measured
  23.4/s on identical settings. The harness has no warm-up phase; do a throwaway run first.
- **Do not use `file` to count PDF pages.** It reports 181 pages for these outputs because
  it greps the first `/Count` it finds, which belongs to an unrelated object. The real
  count is 20. Use a PDF library, or count `/Type /Page` objects.

#### Planning the 10,000-document run

At the best sustained rate observed (~5/s), 10,000 real documents take **~35 minutes** and
produce ~3.2GB of PDF (counted and discarded, not written). Use `concurrency=8`; higher
values measurably hurt on this hardware. To go faster, the lever is more CPU, not more
replicas or more concurrency.

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
