package com.example.docxpoc.service;

import lombok.extern.slf4j.Slf4j;
import org.docx4j.Docx4J;
import org.docx4j.convert.out.FOSettings;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.openpackaging.parts.WordprocessingML.MainDocumentPart;
import org.springframework.stereotype.Service;

import java.io.*;
import java.util.Map;

@Slf4j
@Service
public class DocumentService {

    /**
     * Replace variables in a DOCX file with provided values
     * Variables in the DOCX should be in the format: ${variableName}
     *
     * @param inputStream Input DOCX file stream
     * @param variables Map of variable names to replacement values
     * @return ByteArrayOutputStream containing the modified DOCX
     * @throws Exception if document processing fails
     */
    public ByteArrayOutputStream replaceVariablesInDocx(InputStream inputStream, Map<String, String> variables) throws Exception {
        log.info("Starting variable replacement in DOCX");
        
        // Load the DOCX file
        WordprocessingMLPackage wordMLPackage = WordprocessingMLPackage.load(inputStream);
        MainDocumentPart documentPart = wordMLPackage.getMainDocumentPart();
        
        // Get the document as XML string
        String documentXml = documentPart.getXML();
        log.debug("Original document XML length: {}", documentXml.length());
        
        // Replace each variable
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            String placeholder = "${" + entry.getKey() + "}";
            String value = entry.getValue();
            documentXml = documentXml.replace(placeholder, value);
            log.debug("Replaced {} with {}", placeholder, value);
        }
        
        // Set the modified XML back to the document
        documentPart.setContents(documentXml);
        
        // Save to ByteArrayOutputStream
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        wordMLPackage.save(outputStream);
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
        
        // Load the DOCX file
        WordprocessingMLPackage wordMLPackage = WordprocessingMLPackage.load(docxInputStream);
        
        // Configure PDF output settings
        FOSettings foSettings = Docx4J.createFOSettings();
        foSettings.setWmlPackage(wordMLPackage);
        
        // Create output stream for PDF
        ByteArrayOutputStream pdfOutputStream = new ByteArrayOutputStream();
        
        // Convert to PDF
        Docx4J.toFO(foSettings, pdfOutputStream, Docx4J.FLAG_EXPORT_PREFER_XSL);
        
        log.info("DOCX to PDF conversion completed successfully");
        return pdfOutputStream;
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
