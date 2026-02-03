package com.example.docxpoc.controller;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.deepoove.poi.data.Pictures;
import com.example.docxpoc.entity.Repayments;
import com.example.docxpoc.entity.Signature;
import com.example.docxpoc.service.DocumentService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;

    /**
     * Replace testVariable in uploaded DOCX and convert to PDF
     * POST /api/documents/process
     */
    @PostMapping(value = "/process", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> processDocument(@RequestParam("file") MultipartFile file) {

        try {
            log.info("Received request to process DOCX: {}", file.getOriginalFilename());

            // Always replace testVariable with "I am replaced"
            Map<String, Object> variables = new HashMap<>();
            variables.put("testVariable", "I am replaced");
            variables.put("termLoan", "150,000");
            variables.put("totalAmount", "200,000");
            variables.put("totalRepaymentAmount", "12,000");

            // Add data to repayments table

            variables.put("repayments", new ArrayList<Repayments>(
                    List.of(
                            new Repayments(1, 10000),
                            new Repayments(2, 10000),
                            new Repayments(3, 10000),
                            new Repayments(4, 10000),
                            new Repayments(5, 10000),
                            new Repayments(6, 10000),
                            new Repayments(7, 10000),
                            new Repayments(8, 10000),
                            new Repayments(9, 10000),
                            new Repayments(10, 10000),
                            new Repayments(11, 10000),
                            new Repayments(12, 10000))));

            variables.put("signatureSection", new ArrayList<Signature>(List.of(new Signature(
                    Pictures.ofStream(new ClassPathResource("signature.png").getInputStream())
                            .size(100, 60)
                            .create(),
                    "Test", "2025-09-02", "cow horse", "021103-14-5678"),new Signature(
                    Pictures.ofStream(new ClassPathResource("signature.png").getInputStream())
                            .size(100, 60)
                            .create(),
                    "Test", "2025-09-02", "cow horse", "021103-14-5678"))));

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
