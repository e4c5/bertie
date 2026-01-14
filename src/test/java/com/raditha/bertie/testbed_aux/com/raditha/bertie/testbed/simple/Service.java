package com.raditha.bertie.testbed.simple;
import com.raditha.bertie.testbed.model.User;
import com.raditha.bertie.testbed.repository.Repository;
import com.raditha.bertie.testbed.model.Logger;

public class Service {

    private final Repository<User> repository;
    private final Logger logger;
    public Service() {
        this(null, null);
    }

    public Service(Repository<User> repository, Logger logger) {
        this.repository = repository;
        this.logger = logger;
    }

    void processUser1() {
        User user = repository.findById("123");
        user.setActive(true);
        user.save();
        logger.info("User processed");
    }

    void processUser2() {
        User user = repository.findById("456");
        user.setActive(true);
        user.save();
        logger.info("User processed");
    }
}
