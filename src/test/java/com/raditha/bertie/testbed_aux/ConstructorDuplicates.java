package com.raditha.bertie.testbed_aux;

import java.util.ArrayList;
import java.util.List;

public class ConstructorDuplicates {

    private final List<String> items;
    private final String name;

    public ConstructorDuplicates() {
        // Sequence 1
        this.items = new ArrayList<>();
        this.items.add("default");
        this.name = "default";
        System.out.println("Initialized default constructor");
        validate();
    }

    public ConstructorDuplicates(String name) {
        // Sequence 1 (Duplicate)
        this.items = new ArrayList<>();
        this.items.add("default");
        this.name = name;
        System.out.println("Initialized default constructor"); // Slight variation (name usage above)
        validate();
    }

    public ConstructorDuplicates(int capacity) {
        // Sequence 2
        this.items = new ArrayList<>(capacity);
        for (int i = 0; i < capacity; i++) {
            this.items.add("item" + i);
        }
        this.name = "capacity";
        logCreation();
    }

    public ConstructorDuplicates(double factor) {
        // Sequence 2 (Duplicate)
        int capacity = (int) (factor * 10);
        this.items = new ArrayList<>(capacity);
        for (int i = 0; i < capacity; i++) {
            this.items.add("item" + i);
        }
        this.name = "factor";
        logCreation();
    }

    private void validate() {
        if (name == null) throw new IllegalStateException();
    }

    private void logCreation() {
        System.out.println("Created with name: " + name);
    }
}
