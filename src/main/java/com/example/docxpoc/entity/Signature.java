package com.example.docxpoc.entity;

import com.deepoove.poi.data.PictureRenderData;

public class Signature {
    private PictureRenderData signature;
    private String name;
    private String date;
    private String designation;
    private String nric;

    public Signature(PictureRenderData signature, String name, String date, String designation, String nric) {
        this.signature = signature;
        this.name = name;
        this.date = date;
        this.designation = designation;
        this.nric = nric;
    }

    public PictureRenderData getSignature() {
        return signature;
    }

    public String getName() {
        return name;
    }

    public String getDate() {
        return date;
    }

    public String getDesignation() {
        return designation;
    }

    public String getNric() {
        return nric;
    }
}
