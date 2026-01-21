# Sample Template Instructions

To create a sample DOCX template with variables:

1. Open Microsoft Word or any compatible word processor
2. Create a new document with the following content:

---

**Employment Confirmation Letter**

Date: ${date}

Dear ${name},

We are pleased to confirm your employment with ${company}. Your position will begin on ${startDate}.

Your role will be: ${position}
Department: ${department}
Reporting to: ${manager}

Annual Salary: ${salary}

We look forward to having you as part of our team.

Best regards,

${hrName}
Human Resources Manager
${company}

---

3. Save the file as "sample_template.docx" in this directory

## Variable Names Used:
- ${date} - Current date
- ${name} - Employee name
- ${company} - Company name
- ${startDate} - Employment start date
- ${position} - Job title/position
- ${department} - Department name
- ${manager} - Manager's name
- ${salary} - Annual salary
- ${hrName} - HR manager's name

## Testing the Template:

Use the following curl command to test:

```bash
curl -X POST http://localhost:8080/api/documents/replace-and-convert \
  -F "file=@src/main/resources/templates/sample_template.docx" \
  -F "date=January 21, 2026" \
  -F "name=John Doe" \
  -F "company=Acme Corporation" \
  -F "startDate=February 1, 2026" \
  -F "position=Senior Software Engineer" \
  -F "department=Engineering" \
  -F "manager=Jane Smith" \
  -F "salary=$120,000" \
  -F "hrName=Sarah Johnson" \
  -o output.pdf
```
