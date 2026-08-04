package com.example.docxpoc.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Pins the behaviour of the poi-tl render so an Apache POI upgrade can be verified.
 *
 * <p>The application calls poi-tl only, never Apache POI directly, so a POI upgrade
 * cannot fail at compile time — it fails at runtime inside poi-tl, typically as a
 * {@link NoSuchMethodError}. This test drives the same
 * {@link DocumentService#replaceVariablesInDocx} entry point the endpoint uses and
 * asserts on the rendered document, which is the only way to catch that.
 *
 * <p>Spring and Gotenberg are deliberately not involved: PDF conversion sits
 * downstream of POI and is unaffected by the upgrade.
 *
 * <p>Expected values below were captured from a known-good render on POI 5.2.5.
 */
class PoiTlRenderTest {

    /** The real production template, not a fixture, so the test exercises what ships. */
    private static final Path TEMPLATE =
            Path.of("loadtest", "microLEAP - Template Letter of Offer (Ryt) (Final) (1).docx");

    /** SampleDataFactory supplies 12 monthly repayments, each of 10000. */
    private static final int EXPECTED_REPAYMENT_ROWS = 12;
    private static final String EXPECTED_INSTALMENT = "10000";

    /** Two signature blocks, each carrying one image. */
    private static final int EXPECTED_SIGNATURE_IMAGES = 2;

    private static DocumentService documentService;
    private static SampleDataFactory sampleDataFactory;

    @BeforeAll
    static void setUp() throws Exception {
        // The RestTemplate is only touched by the Gotenberg path, which this test never calls.
        documentService = new DocumentService(null);
        sampleDataFactory = new SampleDataFactory();
        sampleDataFactory.loadSignatureImage();
    }

    private byte[] render() throws Exception {
        Map<String, Object> variables = sampleDataFactory.buildVariables();
        try (InputStream template = Files.newInputStream(TEMPLATE)) {
            ByteArrayOutputStream rendered = documentService.replaceVariablesInDocx(template, variables);
            return rendered.toByteArray();
        }
    }

    @Test
    void rendersWithoutError() throws Exception {
        byte[] rendered = assertDoesNotThrow(this::render);
        assertTrue(rendered.length > 0, "render produced no bytes");
    }

    @Test
    void substitutesScalarVariables() throws Exception {
        String text = allText(render());

        assertTrue(text.contains("I am replaced"), "testVariable was not substituted");
        assertTrue(text.contains("150,000"), "termLoan was not substituted");
        assertTrue(text.contains("200,000"), "totalAmount was not substituted");
        assertTrue(text.contains("12,000"), "totalRepaymentAmount was not substituted");
    }

    @Test
    void leavesNoUnrenderedTags() throws Exception {
        String text = allText(render());

        assertFalse(text.contains("{{"), "unrendered poi-tl tag left in output: " + firstTag(text));
    }

    /**
     * The repayment schedule is a table nested inside a cell of the facility table, so it is
     * invisible to {@code XWPFDocument#getTables()} and has to be reached recursively.
     */
    @Test
    void expandsRepaymentRows() throws Exception {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(render()))) {
            XWPFTable schedule = findRepaymentSchedule(document)
                    .orElseThrow(() -> new AssertionError("repayment schedule table not found"));

            List<XWPFTableRow> rows = schedule.getRows();
            // header + 12 instalments + total
            assertEquals(EXPECTED_REPAYMENT_ROWS + 2, rows.size(),
                    "LoopRowTableRenderPolicy did not expand the expected number of rows");

            for (int month = 1; month <= EXPECTED_REPAYMENT_ROWS; month++) {
                XWPFTableRow row = rows.get(month);
                assertEquals(String.valueOf(month), row.getCell(0).getText().trim(),
                        "wrong month in repayment row " + month);
                assertEquals(EXPECTED_INSTALMENT, row.getCell(1).getText().trim(),
                        "wrong instalment amount in repayment row " + month);
            }
        }
    }

    @Test
    void rendersSignatureBlockFields() throws Exception {
        String text = allText(render());

        assertTrue(text.contains("Name: Test"), "signature name was not rendered");
        assertTrue(text.contains("cow horse"), "signature designation was not rendered");
        assertTrue(text.contains("2025-09-02"), "signature date was not rendered");
    }

    /**
     * Both signature blocks use the same PNG, which POI stores as a single deduplicated image
     * part, so counting {@code getAllPictures()} would under-count. The number of references
     * from the body is what actually determines whether both images appear in the document.
     */
    @Test
    void embedsSignatureImages() throws Exception {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(render()))) {
            long embeddedReferences = document.getParagraphs().stream()
                    .flatMap(paragraph -> paragraph.getRuns().stream())
                    .map(XWPFRun::getEmbeddedPictures)
                    .mapToLong(List::size)
                    .sum();

            assertEquals(EXPECTED_SIGNATURE_IMAGES, embeddedReferences,
                    "wrong number of embedded signature images");
            assertEquals(1, document.getAllPictures().size(),
                    "identical signature images should deduplicate to one image part");
        }
    }

    /**
     * Writes the rendered document to {@code target/} so a human can open it. Layout
     * differences that survive text extraction — spacing, image placement, table borders —
     * only show up visually.
     */
    @Test
    void writesRenderedDocumentForInspection() throws Exception {
        Path output = Path.of("target", "poi-tl-render.docx");
        Files.write(output, render());

        System.out.println("Rendered document written to " + output.toAbsolutePath());
    }

    /** Locates the repayment schedule by its header row rather than by position. */
    private static Optional<XWPFTable> findRepaymentSchedule(XWPFDocument document) {
        return allTables(document).stream()
                .filter(table -> !table.getRows().isEmpty())
                .filter(table -> table.getRow(0).getCell(0) != null
                        && table.getRow(0).getCell(0).getText().trim().equalsIgnoreCase("Month"))
                .findFirst();
    }

    /** Flattens top-level and nested tables, which poi-tl uses for the repayment schedule. */
    private static List<XWPFTable> allTables(XWPFDocument document) {
        List<XWPFTable> tables = new ArrayList<>();
        document.getTables().forEach(table -> collectTables(table, tables));
        return tables;
    }

    private static void collectTables(XWPFTable table, List<XWPFTable> collected) {
        collected.add(table);
        for (XWPFTableRow row : table.getRows()) {
            for (XWPFTableCell cell : row.getTableCells()) {
                cell.getTables().forEach(nested -> collectTables(nested, collected));
            }
        }
    }

    /** Body paragraphs plus every table, nested ones included. */
    private static String allText(byte[] docx) throws Exception {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(docx))) {
            StringBuilder text = new StringBuilder();
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                text.append(paragraph.getText()).append('\n');
            }
            for (XWPFTable table : allTables(document)) {
                text.append(table.getText()).append('\n');
            }
            return text.toString();
        }
    }

    private static String firstTag(String text) {
        int start = text.indexOf("{{");
        return start < 0 ? "" : text.substring(start, Math.min(start + 40, text.length()));
    }
}
