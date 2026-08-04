package com.example.docxpoc.loadtest;

/**
 * One conversion attempt. {@code completedAtEpochMs} is when the PDF became
 * available: the HTTP response in sync mode, the webhook callback in async mode.
 */
public record RequestRecord(
        int requestId,
        String jobId,
        String mode,
        long submittedAtEpochMs,
        long completedAtEpochMs,
        long latencyMs,
        int httpStatus,
        long pdfBytes,
        String error) {

    public boolean succeeded() {
        return error == null;
    }

    static String csvHeader() {
        return "requestId,jobId,mode,submittedAtEpochMs,completedAtEpochMs,latencyMs,httpStatus,pdfBytes,error";
    }

    String toCsvRow() {
        return String.join(",",
                String.valueOf(requestId),
                jobId == null ? "" : jobId,
                mode,
                String.valueOf(submittedAtEpochMs),
                String.valueOf(completedAtEpochMs),
                String.valueOf(latencyMs),
                String.valueOf(httpStatus),
                String.valueOf(pdfBytes),
                csvEscape(error));
    }

    private static String csvEscape(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.replace("\"", "'").replace("\n", " ").replace("\r", " ");
        return "\"" + cleaned + "\"";
    }
}
