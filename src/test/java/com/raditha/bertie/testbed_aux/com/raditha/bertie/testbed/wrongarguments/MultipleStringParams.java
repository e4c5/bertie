package com.raditha.bertie.testbed.wrongarguments;

public class MultipleStringParams {

    public void processAlice() {
        // START DUPLICATE
        String name = "Alice";
        String email = "alice@example.com";
        int age = 25;
        System.out.println("Processing " + name);
        System.out.println("Sending email to " + email);
        // END DUPLICATE
    }

    public void processBob() {
        // START DUPLICATE
        String name = "Bob";
        String email = "bob@example.com";
        int age = 30;
        System.out.println("Processing " + name);
        System.out.println("Sending email to " + email);
        // END DUPLICATE
    }
}
