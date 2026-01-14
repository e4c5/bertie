package com.raditha.bertie.testbed.callsite;

import java.util.HashSet;
import java.util.Set;

/**
 * Test CALL SITE REPLACEMENT for methods with return values.
 *
 * This tests the scenario user described:
 * Duplicate object creation should be extracted to a method,
 * and call sites should be REPLACED with assignments.
 */
public class ServiceWithObjectCreation {

    /**
     * DUPLICATE 1: Creates and initializes DTO
     */
    public void processIncome1() {
        TaxDetails taxDetails = new TaxDetails();
        Set<Integer> incomeTypeIds = new HashSet<>();
        incomeTypeIds.add(1);
        incomeTypeIds.add(3);
        taxDetails.setIncomeTypeIds(incomeTypeIds);
        taxDetails.setFrom(null);
        taxDetails.setTo(null);

        // Use the DTO
        System.out.println("Processing: " + taxDetails);
    }

    /**
     * DUPLICATE 2: Same initialization
     */
    public void processIncome2() {
        TaxDetails taxDetails = new TaxDetails();
        Set<Integer> incomeTypeIds = new HashSet<>();
        incomeTypeIds.add(1);
        incomeTypeIds.add(3);
        taxDetails.setIncomeTypeIds(incomeTypeIds);
        taxDetails.setFrom(null);
        taxDetails.setTo(null);

        // Use the DTO
        int a = taxDetails.getId() + 102;
    }

    /**
     * DUPLICATE 3: Same initialization
     */
    public void processIncome3() {
        TaxDetails taxDetails = new TaxDetails();
        Set<Integer> incomeTypeIds = new HashSet<>();
        incomeTypeIds.add(1);
        incomeTypeIds.add(3);
        taxDetails.setIncomeTypeIds(incomeTypeIds);
        taxDetails.setFrom(null);
        taxDetails.setTo(null);

        // Use the DTO
        System.out.println(taxDetails.getIncomeTypeIds().size());
    }

    // Simple DTO class
    static class TaxDetails {
        private int id;
        private Set<Integer> incomeTypeIds;
        private String from;
        private String to;

        public void setId(int a) {
            id = a;
        }

        public int getId() {
            return id;
        }

        public Set<Integer> getIncomeTypeIds() {
            return incomeTypeIds;
        }

        public void setIncomeTypeIds(Set<Integer> ids) {
            this.incomeTypeIds = ids;
        }

        public void setFrom(String from) {
            this.from = from;
        }

        public void setTo(String to) {
            this.to = to;
        }

        @Override
        public String toString() {
            return "TaxDetails{ids=" + incomeTypeIds + "}";
        }
    }
}
