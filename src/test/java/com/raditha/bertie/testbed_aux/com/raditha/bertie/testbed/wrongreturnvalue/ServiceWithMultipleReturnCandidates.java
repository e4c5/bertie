package com.raditha.bertie.testbed.wrongreturnvalue;

import com.raditha.bertie.testbed.model.User;
import com.raditha.bertie.testbed.repository.Repository;

/**
 * WRONG RETURN VALUE BUG: Returning Incorrect Variable from Extracted Method
 *
 * This class has duplicates with multiple variables of the SAME TYPE.
 * Bertie must detect which variable is actually USED after the duplicate code.
 *
 * BUG SYMPTOM:
 * If Bertie returns the first variable of matching type instead of using
 * data flow analysis, it may return 'tempUser' instead of 'finalUser',
 * causing tests to fail with incorrect data.
 *
 * CRITICAL: Must use data flow analysis, not just "first variable of matching
 * type".
 */
public class ServiceWithMultipleReturnCandidates {

    private final Repository<User> repository;

    public ServiceWithMultipleReturnCandidates(Repository<User> repository) {
        this.repository = repository;
    }

    /**
     * Has TWO User variables - must return the one that's used later (finalUser)
     */
    public String processUserAndReturnCorrectOne(String userId) {
        User tempUser = new User();
        tempUser.setId("temp");
        tempUser.setName("Temporary");

        User finalUser = repository.findById(userId);
        finalUser.setActive(true);
        finalUser.save();

        // Uses finalUser, not tempUser!
        return finalUser.getName() + " is now active";
    }

    /**
     * Similar duplicate - also has TWO User variables
     */
    public String processAnotherUserAndReturnCorrectOne(String userId) {
        User tempUser = new User();
        tempUser.setId("temp");
        tempUser.setName("Temporary");

        User finalUser = repository.findById(userId);
        finalUser.setActive(true);
        finalUser.save();

        // Uses finalUser, not tempUser!
        return String.format("%s was updated", finalUser.getEmail());
    }

    /**
     * Yet another duplicate with same pattern
     */
    public String processThirdUserAndReturnCorrectOne(String userId) {
        User tempUser = new User();
        tempUser.setId("temp");
        tempUser.setName("Temporary");

        User finalUser = repository.findById(userId);
        finalUser.setActive(true);
        finalUser.save();

        // Uses finalUser, not tempUser!
        StringBuilder sb = new StringBuilder();
        sb.append("User ");
        sb.append(finalUser.getId());
        sb.append(" processed");
        return sb.toString();
    }

    /**
     * Duplicate where NO variable is used after (should return void)
     */
    public void processAndDontReturn1(String userId) {
        User user = repository.findById(userId);
        user.setActive(true);
        user.save();

        // No variable used after this point
    }

    /**
     * Another duplicate where NO variable is used after
     */
    public void processAndDontReturn2(String userId) {
        User user = repository.findById(userId);
        user.setActive(true);
        user.save();

        // No variable used after this point
    }
}
