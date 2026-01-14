package com.raditha.bertie.testbed.returnvalue;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Test COLLECTION RETURN TYPES scenario.
 *
 * Duplicates that should return List, Set, or other collection types.
 */
public class ServiceWithCollectionReturns {

    /**
     * DUPLICATE 1: Returns List<String>
     */
    public int processNames1(List<String> names) {
        List<String> filtered = names.stream()
                .filter(n -> n != null)
                .filter(n -> !n.isEmpty())
                .collect(Collectors.toList());

        // Uses 'filtered' - extracted method should return List<String>
        return filtered.size();
    }

    /**
     * DUPLICATE 2: Returns List<String>
     */
    public String getFirst2(List<String> items) {
        List<String> filtered = items.stream()
                .filter(n -> n != null)
                .filter(n -> !n.isEmpty())
                .collect(Collectors.toList());

        // Uses 'filtered' - extracted method should return List<String>
        return filtered.isEmpty() ? null : filtered.get(0);
    }

    /**
     * DUPLICATE 3: Returns List<String>
     */
    public boolean isEmpty3(List<String> values) {
        List<String> filtered = values.stream()
                .filter(n -> n != null)
                .filter(n -> !n.isEmpty())
                .collect(Collectors.toList());

        // Uses 'filtered' - extracted method should return List<String>
        return filtered.isEmpty();
    }
}
