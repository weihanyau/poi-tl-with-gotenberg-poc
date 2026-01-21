# Signature DOCX POC

A Spring Boot application demonstrating dynamic variable replacement in DOCX files using docx4j and PDF conversion.

## Features

- Replace variables in DOCX files (format: `${variableName}`)
- Convert DOCX to PDF
- Combined operation: replace variables and convert to PDF in one step

## Prerequisites

- Java 17 or higher
- Maven 3.6+

## Getting Started

1. **Install dependencies:**
   ```bash
   mvn clean install
   ```

2. **Run the application:**
   ```bash
   mvn spring-boot:run
   ```

   The application will start on `http://localhost:8080`

## API Endpoints

### 1. Health Check
```bash
GET http://localhost:8080/api/documents/health
```

### 2. Replace Variables in DOCX
Replaces variables in a DOCX file and returns the modified DOCX.

```bash
curl -X POST http://localhost:8080/api/documents/replace \
  -F "file=@template.docx" \
  -F "name=John Doe" \
  -F "date=2026-01-21" \
  -F "company=Acme Corp" \
  -o output.docx
```

### 3. Convert DOCX to PDF
Converts a DOCX file to PDF.

```bash
curl -X POST http://localhost:8080/api/documents/convert-to-pdf \
  -F "file=@document.docx" \
  -o output.pdf
```

### 4. Replace Variables and Convert to PDF
Replaces variables and converts to PDF in one operation.

```bash
curl -X POST http://localhost:8080/api/documents/replace-and-convert \
  -F "file=@template.docx" \
  -F "name=John Doe" \
  -F "date=2026-01-21" \
  -F "company=Acme Corp" \
  -o output.pdf
```

## Creating a Template DOCX

Create a DOCX file with variables in the format `${variableName}`. For example:

```
Dear ${name},

This letter is to confirm your employment with ${company} as of ${date}.

Best regards,
HR Department
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
- docx4j 11.4.9
- Apache FOP 2.8
- Lombok

## Notes

- Variables in DOCX templates must be in the format: `${variableName}`
- Maximum file upload size: 10MB
- Supported input format: .docx (Microsoft Word 2007+)
- PDF output uses Apache FOP for rendering

## Troubleshooting

If you encounter PDF conversion issues, ensure:
1. The DOCX file is not corrupted
2. Font files are available on the system
3. Sufficient memory is allocated to the JVM
