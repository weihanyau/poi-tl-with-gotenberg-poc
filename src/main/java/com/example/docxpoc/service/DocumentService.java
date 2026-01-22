package com.example.docxpoc.service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Map;

import org.docx4j.Docx4J;
import org.docx4j.fonts.BestMatchingMapper;
import org.docx4j.fonts.Mapper;
import org.docx4j.model.datastorage.migration.VariablePrepare;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.openpackaging.parts.WordprocessingML.MainDocumentPart;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class DocumentService {

    /**
     * Replace variables in a DOCX file with provided values
     * Variables in the DOCX should be in the format: ${variableName}
     * 
     * Uses docx4j's built-in VariablePrepare and variableReplace methods
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
        
        // Prepare the document for variable replacement
        // This joins up runs that may have been split, which is essential for variable replacement to work
        VariablePrepare.prepare(wordMLPackage);
        log.debug("Document prepared for variable replacement");
        
        // Use docx4j's built-in variableReplace method
        documentPart.variableReplace(variables);
        log.info("Variable replacement completed successfully");
        
        // Save to ByteArrayOutputStream
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        wordMLPackage.save(outputStream);
        
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
        
        // Remove table borders to prevent unwanted borders in PDF
        // removeTableBorders(wordMLPackage);
        
        // Set up font mapper for PDF conversion
        Mapper fontMapper = new BestMatchingMapper();
        wordMLPackage.setFontMapper(fontMapper);
        
        // Create output stream for PDF
        ByteArrayOutputStream pdfOutputStream = new ByteArrayOutputStream();
        
        try {
            // Convert to PDF using Docx4J.toPDF
            Docx4J.toPDF(wordMLPackage, pdfOutputStream);
            
            log.info("DOCX to PDF conversion completed successfully");
        } catch (Exception e) {
            log.error("PDF conversion failed: {}", e.getMessage());
            if (e.getCause() != null) {
                log.error("Caused by: {}", e.getCause().getMessage());
            }
            throw e;
        }
        
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
