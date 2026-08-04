package com.example.docxpoc.controller;

import java.io.ByteArrayOutputStream;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.example.docxpoc.service.DocumentService;
import com.example.docxpoc.service.SampleDataFactory;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;
    private final SampleDataFactory sampleDataFactory;

    /**
     * Replace testVariable in uploaded DOCX and convert to PDF
     * POST /api/documents/process
     */
    @PostMapping(value = "/process", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> processDocument(@RequestParam("file") MultipartFile file) {

        try {
            log.info("Received request to process DOCX: {}", file.getOriginalFilename());

            Map<String, Object> variables = sampleDataFactory.buildVariables();

            ByteArrayOutputStream outputStream = documentService.replaceVariablesAndConvertToPdf(
                    file.getInputStream(),
                    variables);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_PDF);
            String pdfFileName = file.getOriginalFilename().replace(".docx", ".pdf");
            headers.setContentDispositionFormData("attachment", pdfFileName);

            return new ResponseEntity<>(outputStream.toByteArray(), headers, HttpStatus.OK);

        } catch (Exception e) {
            log.error("Error processing DOCX", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}
