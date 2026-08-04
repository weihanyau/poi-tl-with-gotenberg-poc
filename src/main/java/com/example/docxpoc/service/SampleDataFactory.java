package com.example.docxpoc.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.deepoove.poi.data.Pictures;
import com.example.docxpoc.entity.Repayments;
import com.example.docxpoc.entity.Signature;

import jakarta.annotation.PostConstruct;

/**
 * Builds the hardcoded template variables used by both the single-document endpoint
 * and the load test driver, so the two exercise an identical poi-tl render.
 */
@Component
public class SampleDataFactory {

    private byte[] signatureImage;

    @PostConstruct
    void loadSignatureImage() throws IOException {
        try (var in = new ClassPathResource("signature.png").getInputStream()) {
            this.signatureImage = in.readAllBytes();
        }
    }

    /**
     * @return a fresh variable map. Must not be cached and reused across renders:
     *         poi-tl consumes the picture streams during rendering.
     */
    public Map<String, Object> buildVariables() {
        Map<String, Object> variables = new HashMap<>();
        variables.put("testVariable", "I am replaced");
        variables.put("termLoan", "150,000");
        variables.put("totalAmount", "200,000");
        variables.put("totalRepaymentAmount", "12,000");

        List<Repayments> repayments = new ArrayList<>();
        for (int month = 1; month <= 12; month++) {
            repayments.add(new Repayments(month, 10000));
        }
        variables.put("repayments", repayments);

        variables.put("signatureSection", new ArrayList<>(List.of(
                newSignature(),
                newSignature())));

        return variables;
    }

    private Signature newSignature() {
        return new Signature(
                Pictures.ofStream(new ByteArrayInputStream(signatureImage))
                        .size(100, 60)
                        .create(),
                "Test", "2025-09-02", "cow horse", "021103-14-5678");
    }
}
