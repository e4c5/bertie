package com.raditha.bertie.testbed.containers;

/**
 * Test file for duplicate detection in inner class methods.
 * Demonstrates inner class container support.
 */
public class InnerClassMethodDups {
    
    private String outerField = "outer";
    
    public void outerMethod() {
        // DUPLICATE BLOCK START - In outer method
        String data = "test data";
        int count = data.length();
        String result = data.toUpperCase() + "_" + count;
        System.out.println("Result: " + result);
        // DUPLICATE BLOCK END
    }
    
    // Inner class
    public class InnerProcessor {
        private String innerField = "inner";
        
        public void innerMethod() {
            // DUPLICATE BLOCK START - In inner class method
            String data = "test data";
            int count = data.length();
            String result = data.toUpperCase() + "_" + count;
            System.out.println("Result: " + result);
            // DUPLICATE BLOCK END
        }
        
        public void anotherInnerMethod() {
            // Another duplicate in inner class
            String data = "test data";
            int count = data.length();
            String result = data.toUpperCase() + "_" + count;
            System.out.println("Result: " + result);
        }
    }
    
    // Another inner class
    public class AnotherInner {
        public void process() {
            // Duplicate in second inner class
            String data = "test data";
            int count = data.length();
            String result = data.toUpperCase() + "_" + count;
            System.out.println("Result: " + result);
        }
    }
    
    public static void main(String[] args) {
        InnerClassMethodDups outer = new InnerClassMethodDups();
        outer.outerMethod();
        
        InnerProcessor inner = outer.new InnerProcessor();
        inner.innerMethod();
        inner.anotherInnerMethod();
        
        AnotherInner another = outer.new AnotherInner();
        another.process();
    }
}
