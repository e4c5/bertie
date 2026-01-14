package com.raditha.bertie.testbed.repository;

/**
 * Generic repository interface.
 */
public interface Repository<T> {
    T findById(String id);
    T save(T entity);
    void delete(String id);
}
