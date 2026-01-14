package com.raditha.bertie.testbed.filter;

public class ShapeVariations {
    public void drawCircle() {
        int r = 5;
        double area = Math.PI * r * r;
        System.out.println("Circle area: " + area);
        System.out.println("Calculating...");
        System.out.println("Done");
    }

    public void drawSquare() {
        int s = 5;
        double area = s * s;
        System.out.println("Square area: " + area);
        System.out.println("Calculating...");
        System.out.println("Done");
    }

    // Very different method to test filtering
    public void connectDB() {
        String url = "jdbc:mysql://localhost:3306/db";
        System.out.println("Connecting to " + url);
        System.out.println("Step 1");
        System.out.println("Step 2");
        System.out.println("Step 3");
    }
}
