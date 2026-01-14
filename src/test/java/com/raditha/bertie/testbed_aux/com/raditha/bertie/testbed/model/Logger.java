package com.raditha.bertie.testbed.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Mock Logger for testing side effects.
 *
 * Used in Gap 10 tests to verify that logging side effects
 * occur in the correct order and are not duplicated.
 */
public class Logger {

    private final List<String> messages = new ArrayList<>();

    /**
     * Log an info message.
     *
     * @param message the message to log
     */
    public void info(String message) {
        messages.add("INFO: " + message);
    }

    /**
     * Log a warning message.
     *
     * @param message the message to log
     */
    public void warn(String message) {
        messages.add("WARN: " + message);
    }

    /**
     * Log an error message.
     *
     * @param message the message to log
     */
    public void error(String message) {
        messages.add("ERROR: " + message);
    }

    /**
     * Get all logged messages (immutable view).
     *
     * @return list of all messages logged
     */
    public List<String> getMessages() {
        return Collections.unmodifiableList(messages);
    }

    /**
     * Get the number of messages logged.
     *
     * @return message count
     */
    public int getMessageCount() {
        return messages.size();
    }

    /**
     * Clear all logged messages.
     */
    public void clear() {
        messages.clear();
    }

    /**
     * Check if a specific message was logged.
     *
     * @param message the message to find
     * @return true if message was logged
     */
    public boolean hasMessage(String message) {
        return messages.stream().anyMatch(m -> m.contains(message));
    }
}
