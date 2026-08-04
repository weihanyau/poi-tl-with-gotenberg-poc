package com.example.docxpoc.service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import com.deepoove.poi.XWPFTemplate;
import com.deepoove.poi.config.Configure;
import com.deepoove.poi.plugin.table.LoopRowTableRenderPolicy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    @Value("${gotenberg.url}")
    private String gotenbergUrl;

    /** Pooled and timeout-bounded; see {@code HttpClientConfig}. */
    private final RestTemplate gotenbergRestTemplate;

    /**
     * Replace variables in a DOCX file with provided values using poi-tl
     * Variables in the DOCX should be in the format: {{variableName}}
     * 
     * Uses poi-tl template engine for variable replacement
     *
     * @param inputStream Input DOCX file stream
     * @param variables Map of variable names to replacement values
     * @return ByteArrayOutputStream containing the modified DOCX
     * @throws Exception if document processing fails
     */
    public ByteArrayOutputStream replaceVariablesInDocx(InputStream inputStream, Map<String, Object> variables) throws Exception {
        log.info("Starting variable replacement in DOCX using poi-tl");

        LoopRowTableRenderPolicy loopRowTableRenderPolicy = new LoopRowTableRenderPolicy();
        
        // Configure poi-tl with custom settings if needed
        Configure config = Configure.builder().bind("repayments", loopRowTableRenderPolicy).build();

        // Create template from input stream
        XWPFTemplate template = XWPFTemplate.compile(inputStream, config).render(variables);

        
        // Save to ByteArrayOutputStream
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        template.write(outputStream);
        template.close();
        
        log.info("Variable replacement completed successfully");
        return outputStream;
    }

    /**
     * Convert a DOCX file to PDF using Gotenberg service
     *
     * @param docxInputStream Input DOCX file stream
     * @return ByteArrayOutputStream containing the generated PDF
     * @throws Exception if conversion fails
     */
    public ByteArrayOutputStream convertDocxToPdf(InputStream docxInputStream) throws Exception {
        log.info("Starting DOCX to PDF conversion using Gotenberg");
        
        try {
            // Read DOCX into byte array
            byte[] docxBytes = docxInputStream.readAllBytes();
            
            // Prepare multipart request for Gotenberg
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            
            // Add the DOCX file
            ByteArrayResource fileResource = new ByteArrayResource(docxBytes) {
                @Override
                public String getFilename() {
                    return "document.docx";
                }
            };
            body.add("files", fileResource);
            
            HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);
            
            // Call Gotenberg API
            String gotenbergEndpoint = gotenbergUrl + "/forms/libreoffice/convert";
            log.info("Calling Gotenberg at: {}", gotenbergEndpoint);
            
            ResponseEntity<byte[]> response = gotenbergRestTemplate.postForEntity(
                gotenbergEndpoint,
                requestEntity,
                byte[].class
            );
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                outputStream.write(response.getBody());
                log.info("DOCX to PDF conversion completed successfully");
                return outputStream;
            } else {
                throw new RuntimeException("Gotenberg conversion failed with status: " + response.getStatusCode());
            }
            
        } catch (Exception e) {
            log.error("PDF conversion failed: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to convert DOCX to PDF using Gotenberg: " + e.getMessage(), e);
        }
    }


    /**
     * Replace variables in a DOCX and convert to PDF in one operation
     *
     * @param inputStream Input DOCX file stream
     * @param variables Map of variable names to replacement values
     * @return ByteArrayOutputStream containing the generated PDF
     * @throws Exception if processing fails
     */
    public ByteArrayOutputStream replaceVariablesAndConvertToPdf(InputStream inputStream, Map<String, Object> variables) throws Exception {
        log.info("Starting variable replacement and PDF conversion");
        
        // First replace variables
        ByteArrayOutputStream docxWithReplacements = replaceVariablesInDocx(inputStream, variables);
        
        // Then convert to PDF
        ByteArrayInputStream modifiedDocxStream = new ByteArrayInputStream(docxWithReplacements.toByteArray());
        ByteArrayOutputStream pdfOutput = convertDocxToPdf(modifiedDocxStream);
        
        log.info("Variable replacement and PDF conversion completed successfully");
        return pdfOutput;
    }
}
