package com.example.docxpoc.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Talks to Gotenberg's LibreOffice conversion route, in either blocking mode or
 * webhook (fire-and-forget) mode.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GotenbergClient {

    private final RestTemplate gotenbergRestTemplate;

    @Value("${gotenberg.url}")
    private String gotenbergUrl;

    /** Outcome of a blocking conversion. The PDF itself is deliberately not retained. */
    public record ConversionResult(int httpStatus, long pdfBytes) {
    }

    /**
     * Converts synchronously, blocking until Gotenberg returns the PDF.
     *
     * @return the HTTP status and the size of the returned PDF
     */
    public ConversionResult convertSync(byte[] docxBytes, String filename) {
        HttpEntity<MultiValueMap<String, Object>> requestEntity = buildMultipartRequest(docxBytes, filename,
                new HttpHeaders());

        ResponseEntity<byte[]> response = gotenbergRestTemplate.postForEntity(
                convertEndpoint(), requestEntity, byte[].class);

        byte[] body = response.getBody();
        return new ConversionResult(response.getStatusCode().value(), body == null ? 0L : body.length);
    }

    /**
     * Submits a conversion and returns as soon as Gotenberg accepts the job. Gotenberg
     * POSTs the finished PDF to {@code successUrl}, or the failure detail to
     * {@code errorUrl}.
     *
     * @return the HTTP status of the submission acknowledgement (expected 204)
     */
    public int submitWebhook(byte[] docxBytes, String filename, String successUrl, String errorUrl, String traceId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Gotenberg-Webhook-Url", successUrl);
        headers.set("Gotenberg-Webhook-Method", "POST");
        headers.set("Gotenberg-Webhook-Error-Url", errorUrl);
        headers.set("Gotenberg-Webhook-Error-Method", "POST");
        // Surfaces our job id in Gotenberg's own logs, which makes correlating a
        // missing callback with a server-side error possible.
        headers.set("Gotenberg-Trace", traceId);

        HttpEntity<MultiValueMap<String, Object>> requestEntity = buildMultipartRequest(docxBytes, filename, headers);

        ResponseEntity<Void> response = gotenbergRestTemplate.postForEntity(
                convertEndpoint(), requestEntity, Void.class);

        return response.getStatusCode().value();
    }

    private HttpEntity<MultiValueMap<String, Object>> buildMultipartRequest(byte[] docxBytes, String filename,
            HttpHeaders headers) {
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("files", new ByteArrayResource(docxBytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        });

        return new HttpEntity<>(body, headers);
    }

    private String convertEndpoint() {
        return gotenbergUrl + "/forms/libreoffice/convert";
    }
}
