# Sample Template Instructions

To create a sample DOCX template:

1. Open Microsoft Word or any compatible word processor
2. Create a new document with the following content:

---

**Test Document**

This is a test document for variable replacement.

The test variable is: ${testVariable}

This will be automatically replaced with "I am replaced" and converted to PDF.

---

3. Save the file as "sample_template.docx" in this directory

## Variable Used:
- ${testVariable} - This will be replaced with "I am replaced"

## Testing the Template:

Use the following curl command to test:

```bash
curl -X POST http://localhost:8080/api/documents/process \
  -F "file=@src/main/resources/templates/sample_template.docx" \
  -o output.pdf
```

The endpoint will:
1. Replace ${testVariable} with "I am replaced"
2. Convert the document to PDF
3. Return the PDF file

