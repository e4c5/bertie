package com.raditha.bertie.testbed.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Mock Database for testing side effects.
 *
 * Used in Gap 10 tests to verify that database operations
 * occur in the correct order and are not duplicated.
 */
public class Database {

    private final List<String> savedEntities = new ArrayList<>();
    private final List<String> deletedEntities = new ArrayList<>();

    /**
     * Save an entity to the database.
     *
     * @param entity the entity to save
     */
    public void save(Object entity) {
        savedEntities.add(entity.toString());
    }

    /**
     * Delete an entity from the database.
     *
     * @param entity the entity to delete
     */
    public void delete(Object entity) {
        deletedEntities.add(entity.toString());
    }

    /**
     * Get all saved entities (immutable view).
     *
     * @return list of all saved entities
     */
    public List<String> getSavedEntities() {
        return Collections.unmodifiableList(savedEntities);
    }

    /**
     * Get all deleted entities (immutable view).
     *
     * @return list of all deleted entities
     */
    public List<String> getDeletedEntities() {
        return Collections.unmodifiableList(deletedEntities);
    }

    /**
     * Get the number of save operations performed.
     *
     * @return save operation count
     */
    public int getSaveCount() {
        return savedEntities.size();
    }

    /**
     * Get the number of delete operations performed.
     *
     * @return delete operation count
     */
    public int getDeleteCount() {
        return deletedEntities.size();
    }

    /**
     * Clear all operation history.
     */
    public void clear() {
        savedEntities.clear();
        deletedEntities.clear();
    }

    /**
     * Check if a specific entity was saved.
     *
     * @param entityString the entity string to find
     * @return true if entity was saved
     */
    public boolean wasSaved(String entityString) {
        return savedEntities.contains(entityString);
    }
}
