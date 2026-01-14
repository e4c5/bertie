package com.raditha.bertie.testbed.crossfile;

public class ShippingService {
    // Duplicate of checkStock but for shipping
    public void validateItem(String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            throw new IllegalArgumentException("Invalid item ID");
        }
        System.out.println("Validating item " + itemId);
        System.out.println("Step 1");
        System.out.println("Step 2");
        // Simulate DB call
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}
