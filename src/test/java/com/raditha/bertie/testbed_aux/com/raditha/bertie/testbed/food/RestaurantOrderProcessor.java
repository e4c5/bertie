package com.raditha.bertie.testbed.food;

/**
 * RestaurantOrderProcessor demonstrates duplication in order processing and
 * financial calculations.
 * It contains duplicated logic for tax calculation, discount application, and
 * final dispatch.
 */
public class RestaurantOrderProcessor {

    public void processDineInOrder(double subtotal, String tableNumber, boolean isVIP) {
        // START DUPLICATE: Initial Validation and Prep
        if (subtotal <= 0) {
            throw new IllegalArgumentException("Subtotal must be positive");
        }
        System.out.println("Processing order...");
        double finalAmount = subtotal;
        // END DUPLICATE

        // Add service charge for dine-in
        finalAmount += subtotal * 0.10;

        if (isVIP) {
            finalAmount *= 0.90; // 10% VIP discount
        }

        // START DUPLICATE: Tax and Dispatch
        double tax = finalAmount * 0.05;
        finalAmount += tax;
        System.out.println("LOG: Final amount calculated: $" + finalAmount);
        System.out.println("LOG: Dispatching order to register...");
        notifyServer("Register", finalAmount);
        // END DUPLICATE

        System.out.println("Order assigned to table: " + tableNumber);
    }

    public void processTakeoutOrder(double subtotal, boolean applyBagFee, boolean hasCoupon) {
        // START DUPLICATE: Initial Validation and Prep
        if (subtotal <= 0) {
            throw new IllegalArgumentException("Subtotal must be positive");
        }
        System.out.println("Processing order...");
        double finalAmount = subtotal;
        // END DUPLICATE

        if (applyBagFee) {
            finalAmount += 0.50; // Packaging fee
        }

        if (hasCoupon) {
            finalAmount -= 2.00; // Flat coupon discount
        }

        // START DUPLICATE: Tax and Dispatch
        double tax = finalAmount * 0.05;
        finalAmount += tax;
        System.out.println("LOG: Final amount calculated: $" + finalAmount);
        System.out.println("LOG: Dispatching order to register...");
        notifyServer("Register", finalAmount);
        // END DUPLICATE

        System.out.println("Takeout order ready for pickup");
    }

    public void processDeliveryOrder(double subtotal, double distance, boolean express) {
        // START DUPLICATE: Initial Validation and Prep
        if (subtotal <= 0) {
            throw new IllegalArgumentException("Subtotal must be positive");
        }
        System.out.println("Processing order...");
        double finalAmount = subtotal;
        // END DUPLICATE

        double deliveryFee = distance * 1.5;
        if (express) {
            deliveryFee += 5.0;
        }
        finalAmount += deliveryFee;

        // START DUPLICATE: Tax and Dispatch
        double tax = finalAmount * 0.05;
        finalAmount += tax;
        System.out.println("LOG: Final amount calculated: $" + finalAmount);
        System.out.println("LOG: Dispatching order to register...");
        notifyServer("Register", finalAmount);
        // END DUPLICATE

        System.out.println("Delivery dispatched for distance: " + distance + "km");
    }

    private void notifyServer(String channel, double amount) {
        System.out.println("Notifying " + channel + " of order total: " + amount);
    }
}
