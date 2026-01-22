# Signature DOCX POC

A Spring Boot application demonstrating dynamic variable replacement in DOCX files using docx4j and PDF conversion.

## Features

- Automatically replaces `${testVariable}` with "I am replaced" in DOCX files
- Converts the processed DOCX to PDF
- Single endpoint for streamlined processing

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

## API Endpoint

### Process DOCX Document
Replaces `${testVariable}` with "I am replaced" in the uploaded DOCX file and returns a PDF.

```bash
curl -X POST http://localhost:8080/api/documents/process \
  -F "file=@template.docx" \
  -o output.pdf
```

## Creating a Template DOCX

Create a DOCX file with the variable `${testVariable}`. For example:

```
This is a test document.

The test variable is: ${testVariable}

This will be replaced automatically.
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
