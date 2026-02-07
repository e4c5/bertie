package com.raditha.bertie.testbed.containers;

/**
 * Test file for duplicate detection in anonymous class methods.
 * Demonstrates anonymous class container support (detection only in Phase 1).
 */
public class AnonymousClassDups {
    
    interface Processor {
        void process(String input);
        String format(String input);
    }
    
    public void useAnonymousClasses() {
        // Anonymous class #1 with duplicate code
        Processor processor1 = new Processor() {
            @Override
            public void process(String input) {
                // DUPLICATE BLOCK START
                String trimmed = input.trim();
                String upper = trimmed.toUpperCase();
                int len = upper.length();
                String output = upper + "_" + len;
                System.out.println("Output: " + output);
                // DUPLICATE BLOCK END
            }
            
            @Override
            public String format(String input) {
                return input.toLowerCase();
            }
        };
        
        // Anonymous class #2 with duplicate code
        Processor processor2 = new Processor() {
            @Override
            public void process(String input) {
                // DUPLICATE BLOCK START
                String trimmed = input.trim();
                String upper = trimmed.toUpperCase();
                int len = upper.length();
                String output = upper + "_" + len;
                System.out.println("Output: " + output);
                // DUPLICATE BLOCK END
            }
            
            @Override
            public String format(String input) {
                return input.toUpperCase();
            }
        };
        
        processor1.process("  test  ");
        processor2.process("  example  ");
    }
    
    public void regularMethod(String input) {
        // Regular method with duplicate code
        String trimmed = input.trim();
        String upper = trimmed.toUpperCase();
        int len = upper.length();
        String output = upper + "_" + len;
        System.out.println("Output: " + output);
    }
    
    public Runnable createRunnable() {
        // Anonymous Runnable with duplicate
        return new Runnable() {
            @Override
            public void run() {
                String input = "runnable";
                String trimmed = input.trim();
                String upper = trimmed.toUpperCase();
                int len = upper.length();
                String output = upper + "_" + len;
                System.out.println("Output: " + output);
            }
        };
    }
    
    public static void main(String[] args) {
        AnonymousClassDups test = new AnonymousClassDups();
        test.useAnonymousClasses();
        test.regularMethod("  main  ");
        test.createRunnable().run();
    }
}
