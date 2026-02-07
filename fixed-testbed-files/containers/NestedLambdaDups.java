package com.raditha.bertie.testbed.containers;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Test file for duplicate detection across method and nested lambda scopes.
 * Demonstrates cross-container duplicate detection and nested scope resolution.
 */
public class NestedLambdaDups {
    
    public List<String> processItems(List<String> items) {
        // DUPLICATE BLOCK START - In method
        String prefix = "ITEM_";
        int minLength = 3;
        String suffix = "_PROCESSED";
        String formatted = prefix + minLength + suffix;
        System.out.println("Format: " + formatted);
        // DUPLICATE BLOCK END
        
        // Lambda with nested duplicate
        return items.stream()
            .map(item -> {
                // DUPLICATE BLOCK START - In lambda
                String prefix2 = "ITEM_";
                int minLength2 = 3;
                String suffix2 = "_PROCESSED";
                String formatted2 = prefix2 + minLength2 + suffix2;
                System.out.println("Format: " + formatted2);
                // DUPLICATE BLOCK END
                return item.toUpperCase();
            })
            .collect(Collectors.toList());
    }
    
    public void filterAndProcess() {
        List<Integer> numbers = Arrays.asList(1, 2, 3, 4, 5);
        
        numbers.stream()
            .filter(n -> {
                // Duplicate in filter lambda
                String prefix = "ITEM_";
                int minLength = 3;
                String suffix = "_PROCESSED";
                String formatted = prefix + minLength + suffix;
                System.out.println("Format: " + formatted);
                return n > 2;
            })
            .forEach(n -> System.out.println(n));
    }
    
    public void anotherMethod() {
        // Duplicate in another method
        String prefix = "ITEM_";
        int minLength = 3;
        String suffix = "_PROCESSED";
        String formatted = prefix + minLength + suffix;
        System.out.println("Format: " + formatted);
    }
    
    public static void main(String[] args) {
        NestedLambdaDups test = new NestedLambdaDups();
        test.processItems(Arrays.asList("a", "b", "c"));
        test.filterAndProcess();
    }
}
