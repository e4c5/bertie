package com.raditha.bertie.testbed.repository;

import java.util.List;
import java.util.ArrayList;

/**
 * Repository interface with methods needed for container duplicate tests.
 * This file adds the findAll() method to the existing Repository class.
 */
public interface Repository<T> {
    
    void save(T entity);
    
    T findById(Long id);
    
    void delete(T entity);
    
    // ADDED METHOD BELOW
    
    /**
     * Finds all entities.
     * @return List of all entities
     */
    List<T> findAll();
}
