package com.raditha.bertie.testbed.partial;

public class MixedResponsibilityService {
    public void hugeMethod() {
        // ... some code ...
        int x = 10;
        int y = 20;
        int sum = x + y;
        System.out.println("Sum: " + sum);

        // Duplicate part
        for (int i = 0; i < 10; i++) {
            System.out.println("Processing " + i);
            System.out.println("Step 1");
            System.out.println("Step 2");
            System.out.println("Step 3");
            if (i % 2 == 0) {
                System.out.println("Even");
            }
        }

        // ... more code ...
    }

    public void anotherMethod() {
        // Duplicate part (matches part of hugeMethod)
        for (int i = 0; i < 10; i++) {
            System.out.println("Processing " + i);
            System.out.println("Step 1");
            System.out.println("Step 2");
            System.out.println("Step 3");
            if (i % 2 == 0) {
                System.out.println("Even");
            }
        }
    }
}
