package com.raditha.bertie.testbed.food;

import java.util.List;

/**
 * KitchenOperations demonstrates duplicated code in dish preparation workflows.
 * It contains common setup and cleanup logic across different cooking methods.
 */
public class KitchenOperations {

    public void cookPasta(String sauce, int grams) {
        // START DUPLICATE: Setup
        System.out.println("LOG: Activating kitchen station...");
        checkInventory("Ingredients");
        long startTime = System.currentTimeMillis();
        // END DUPLICATE

        System.out.println("Boiling " + grams + "g of pasta for " + sauce);
        simulateCooking(10);

        // START DUPLICATE: Cleanup
        long endTime = System.currentTimeMillis();
        System.out.println("LOG: Preparation completed in " + (endTime - startTime) + "ms");
        sanitizeStation();
        updateServiceLogs("SUCCESS");
        // END DUPLICATE
    }

    public void cookSoup(String base, int ml) {
        // START DUPLICATE: Setup
        System.out.println("LOG: Activating kitchen station...");
        checkInventory("Ingredients");
        long startTime = System.currentTimeMillis();
        // END DUPLICATE

        System.out.println("Simmering " + ml + "ml of soup with " + base + " base");
        simulateCooking(20);

        // START DUPLICATE: Cleanup
        long endTime = System.currentTimeMillis();
        System.out.println("LOG: Preparation completed in " + (endTime - startTime) + "ms");
        sanitizeStation();
        updateServiceLogs("SUCCESS");
        // END DUPLICATE
    }

    public void grillSteak(String cut, String doneness) {
        // START DUPLICATE: Setup
        System.out.println("LOG: Activating kitchen station...");
        checkInventory("Ingredients");
        long startTime = System.currentTimeMillis();
        // END DUPLICATE

        System.out.println("Grilling " + cut + " steak to " + doneness);
        simulateCooking(15);

        // START DUPLICATE: Cleanup
        long endTime = System.currentTimeMillis();
        System.out.println("LOG: Preparation completed in " + (endTime - startTime) + "ms");
        sanitizeStation();
        updateServiceLogs("SUCCESS");
        // END DUPLICATE
    }

    private void checkInventory(String item) {
        System.out.println("Inventory check for: " + item);
    }

    private void simulateCooking(int minutes) {
        System.out.println("Cooking for " + minutes + " minutes...");
    }

    private void sanitizeStation() {
        System.out.println("Sanitizing kitchen station...");
    }

    private void updateServiceLogs(String status) {
        System.out.println("Updating service log status: " + status);
    }
}
