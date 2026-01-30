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

## Creating a Template DOCX

Create a DOCX file with variables in the format `{{variableName}}`. For example:

```
This is a test document.

The test variable is: {{testVariable}}
Term Loan: {{termLoan}}
Total Amount: {{totalAmount}}

These will be replaced automatically.
```

A sample template is provided in `src/main/resources/templates/sample_template.docx`.

## Project Structure

```
signature-docx-poc/
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── com/example/docxpoc/
│   │   │       ├── DocxPocApplication.java
│   │   │       ├── controller/
│   │   │       │   └── DocumentController.java
│   │   │       └── service/
│   │   │           └── DocumentService.java
│   │   └── resources/
│   │       ├── application.properties
│   │       └── templates/
│   │           └── sample_template.docx
│   └── test/
└── pom.xml
```

## Dependencies

- Spring Boot 3.2.1
- docx4j 11.5.9
- Apache FOP 2.8
- Lombok

## Notes

- The variable `${testVariable}` is automatically replaced with "I am replaced"
- Maximum file upload size: 10MB
- Supported input format: .docx (Microsoft Word 2007+)
- Output format: PDF
- PDF output uses Apache FOP for rendering

## Troubleshooting

If you encounter PDF conversion issues, ensure:
1. The DOCX file is not corrupted
2. Font files are available on the system
3. Sufficient memory is allocated to the JVM
