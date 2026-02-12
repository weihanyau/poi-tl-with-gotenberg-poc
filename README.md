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

## API Endpoint

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
├── docker-compose.yml
├── Dockerfile
└── pom.xml
```

## Dependencies

- Spring Boot 3.2.1
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
