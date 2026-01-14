package com.raditha.bertie.testbed.sideeffects;

import com.raditha.bertie.testbed.model.Database;
import com.raditha.bertie.testbed.model.Logger;
import com.raditha.bertie.testbed.model.User;

/**
 * Service demonstrating Gap 10: No Validation That Extracted Code is
 * Side-Effect Free
 *
 * This class has duplicate code with observable side effects (database,
 * logging).
 * Side effects must execute in the correct order and not be duplicated.
 *
 * CRITICAL: After refactoring, side effect order and count must remain
 * unchanged!
 */
public class ServiceWithDatabaseAndLogging {

    private final Database database;
    private final Logger logger;

    public ServiceWithDatabaseAndLogging(Database database, Logger logger) {
        this.database = database;
        this.logger = logger;
    }

    /**
     * Process user with database and logging side effects.
     *
     * Side effects MUST occur in this order:
     * 1. Log "Starting"
     * 2. Save to database
     * 3. Log "Completed"
     */
    public void processUserWithLogging(String userName) {
        // Duplicate code with side effects starts here
        logger.info("Starting processing for: " + userName);

        User user = new User();
        user.setName(userName);
        user.setActive(true);
        database.save(user);

        logger.info("Completed processing for: " + userName);
        // Duplicate code with side effects ends here
    }

    /**
     * Process another user with same side effect pattern.
     * Same order: Log → Save → Log
     */
    public void processAnotherUserWithLogging(String userName) {
        // Duplicate code with side effects starts here
        logger.info("Starting processing for: " + userName);

        User user = new User();
        user.setName(userName);
        user.setActive(true);
        database.save(user);

        logger.info("Completed processing for: " + userName);
        // Duplicate code with side effects ends here
    }

    /**
     * Process third user with same side effect pattern.
     * Same order: Log → Save → Log
     */
    public void processThirdUserWithLogging(String userName) {
        // Duplicate code with side effects starts here
        logger.info("Starting processing for: " + userName);

        User user = new User();
        user.setName(userName);
        user.setActive(true);
        database.save(user);

        logger.info("Completed processing for: " + userName);
        // Duplicate code with side effects ends here
    }

    /**
     * Complex workflow with multiple interleaved side effects.
     *
     * Side effect order:
     * 1. Log "Validating"
     * 2. Save user
     * 3. Log "Saved"
     * 4. Save audit log
     * 5. Log "Audit recorded"
     */
    public void processWithMultipleSideEffects(String userName) {
        // Complex duplicate with interleaved side effects
        logger.info("Validating user: " + userName);

        User user = new User();
        user.setName(userName);
        user.setActive(true);
        database.save(user);

        logger.info("Saved user: " + userName);

        // Audit logging
        String auditEntry = "User " + userName + " processed";
        database.save(auditEntry);

        logger.info("Audit recorded for: " + userName);
        // Complex duplicate ends here
    }

    /**
     * Another complex workflow with same side effect pattern.
     */
    public void processAnotherWithMultipleSideEffects(String userName) {
        // Complex duplicate with interleaved side effects
        logger.info("Validating user: " + userName);

        User user = new User();
        user.setName(userName);
        user.setActive(true);
        database.save(user);

        logger.info("Saved user: " + userName);

        // Audit logging
        String auditEntry = "User " + userName + " processed";
        database.save(auditEntry);

        logger.info("Audit recorded for: " + userName);
        // Complex duplicate ends here
    }
}
