package com.example.docxpoc.service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Map;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Service;

import com.deepoove.poi.XWPFTemplate;
import com.deepoove.poi.config.Configure;

import fr.opensagres.poi.xwpf.converter.pdf.PdfConverter;
import fr.opensagres.poi.xwpf.converter.pdf.PdfOptions;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class DocumentService {

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
    public ByteArrayOutputStream replaceVariablesInDocx(InputStream inputStream, Map<String, String> variables) throws Exception {
        log.info("Starting variable replacement in DOCX using poi-tl");
        
        // Configure poi-tl with custom settings if needed
        Configure config = Configure.builder().build();
        
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
     * Convert a DOCX file to PDF
     *
     * @param docxInputStream Input DOCX file stream
     * @return ByteArrayOutputStream containing the generated PDF
     * @throws Exception if conversion fails
     */
    public ByteArrayOutputStream convertDocxToPdf(InputStream docxInputStream) throws Exception {
        log.info("Starting DOCX to PDF conversion");
        
        try {
            // Load the DOCX file using Apache POI
            XWPFDocument document = new XWPFDocument(docxInputStream);
            
            // Create output stream for PDF
            ByteArrayOutputStream pdfOutputStream = new ByteArrayOutputStream();
            
            // Configure PDF options with font encoding
            PdfOptions options = PdfOptions.create();
            
            // Enable font encoding to preserve fonts better
            options.fontEncoding("UTF-8");
            
            // Convert to PDF using fr.opensagres.xdocreport
            PdfConverter.getInstance().convert(document, pdfOutputStream, options);
            
            document.close();
            
            log.info("DOCX to PDF conversion completed successfully");
            return pdfOutputStream;
            
        } catch (Exception e) {
            log.error("PDF conversion failed: {}", e.getMessage());
            if (e.getCause() != null) {
                log.error("Caused by: {}", e.getCause().getMessage());
            }
            throw e;
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
    public ByteArrayOutputStream replaceVariablesAndConvertToPdf(InputStream inputStream, Map<String, String> variables) throws Exception {
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
