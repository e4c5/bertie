package com.raditha.bertie.testbed.crossfile;

public class InventoryService {
    public void checkStock(String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            throw new IllegalArgumentException("Invalid item ID");
        }
        System.out.println("Checking stock for " + itemId);
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
