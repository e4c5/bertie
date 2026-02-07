package com.raditha.bertie.testbed.containers;

/**
 * Test file for duplicate detection in static inner class methods.
 * Demonstrates static inner class container support and static context detection.
 */
public class StaticInnerClassDups {
    
    private static String staticField = "static_field";
    
    public static void staticOuterMethod() {
        // DUPLICATE BLOCK START - In static outer method
        int value = 42;
        String message = "Value is: " + value;
        boolean isValid = value > 0;
        System.out.println(message + ", valid: " + isValid);
        // DUPLICATE BLOCK END
    }
    
    // Static inner class
    public static class StaticInnerProcessor {
        private static String innerStatic = "inner_static";
        
        public static void staticInnerMethod() {
            // DUPLICATE BLOCK START - In static inner class method
            int value = 42;
            String message = "Value is: " + value;
            boolean isValid = value > 0;
            System.out.println(message + ", valid: " + isValid);
            // DUPLICATE BLOCK END
        }
        
        public void instanceInnerMethod() {
            // Duplicate in instance method of static inner class
            int value = 42;
            String message = "Value is: " + value;
            boolean isValid = value > 0;
            System.out.println(message + ", valid: " + isValid);
        }
    }
    
    // Another static inner class
    public static class AnotherStaticInner {
        public void process() {
            // Duplicate in second static inner class
            int value = 42;
            String message = "Value is: " + value;
            boolean isValid = value > 0;
            System.out.println(message + ", valid: " + isValid);
        }
    }
    
    public static void main(String[] args) {
        staticOuterMethod();
        StaticInnerProcessor.staticInnerMethod();
        
        StaticInnerProcessor processor = new StaticInnerProcessor();
        processor.instanceInnerMethod();
        
        AnotherStaticInner another = new AnotherStaticInner();
        another.process();
    }
}
