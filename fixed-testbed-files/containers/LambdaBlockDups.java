package com.raditha.bertie.testbed.containers;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Test file for duplicate detection in lambda expression blocks.
 * Demonstrates LAMBDA container type support.
 */
public class LambdaBlockDups {
    
    public void processWithLambdas() {
        List<String> items = Arrays.asList("apple", "banana", "cherry");
        
        // Lambda #1 with block body containing duplicate code
        Consumer<String> processor1 = (item) -> {
            // DUPLICATE BLOCK START
            String processed = item.toUpperCase();
            int length = processed.length();
            String result = processed + "_" + length;
            System.out.println("Processed: " + result);
            // DUPLICATE BLOCK END
        };
        
        // Lambda #2 with block body containing duplicate code
        Consumer<String> processor2 = (item) -> {
            // DUPLICATE BLOCK START
            String processed = item.toUpperCase();
            int length = processed.length();
            String result = processed + "_" + length;
            System.out.println("Processed: " + result);
            // DUPLICATE BLOCK END
        };
        
        items.forEach(processor1);
        items.forEach(processor2);
    }
    
    public Function<String, String> createFormatter() {
        // Lambda with duplicate code
        return (input) -> {
            String processed = input.toUpperCase();
            int length = processed.length();
            String result = processed + "_" + length;
            System.out.println("Processed: " + result);
            return result;
        };
    }
    
    public void processInMethod(String item) {
        // Method with duplicate code
        String processed = item.toUpperCase();
        int length = processed.length();
        String result = processed + "_" + length;
        System.out.println("Processed: " + result);
    }
    
    public static void main(String[] args) {
        LambdaBlockDups test = new LambdaBlockDups();
        test.processWithLambdas();
    }
}
