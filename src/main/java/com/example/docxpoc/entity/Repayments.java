package com.example.docxpoc.entity;

public class Repayments {
    private int month;
    private int amount;

    public Repayments(int month, int amount) {
        this.month = month;
        this.amount = amount;
    }

    public int getMonth() {
        return month;
    }

    public int getAmount() {
        return amount;
    }

    public void setMonth(int month) {
        this.month = month;
    }

    public void setAmount(int amount) {
        this.amount = amount;
    }
}
