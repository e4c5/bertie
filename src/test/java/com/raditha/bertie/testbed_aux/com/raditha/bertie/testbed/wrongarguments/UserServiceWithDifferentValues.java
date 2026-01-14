package com.raditha.bertie.testbed.wrongarguments;

import com.raditha.bertie.testbed.model.Customer;
import com.raditha.bertie.testbed.model.User;
import com.raditha.bertie.testbed.repository.Repository;

/**
 * WRONG ARGUMENTS BUG: Incorrect Parameter Values in Extracted Method Calls
 *
 * This class has duplicate code with DIFFERENT VALUES for variables.
 * After refactoring, Bertie MUST use the correct values for each method.
 *
 * EXPECTED BEHAVIOR:
 * - processJohn() should use "John" and "john@example.com"
 * - processJane() should use "Jane" and "jane@example.com"
 * - processAdmin() should use "Admin" and "admin@example.com"
 *
 * BUG SYMPTOM:
 * If Bertie uses exampleValues.get(0) for all call sites, all methods
 * will use the same argument values (from the primary duplicate), causing
 * tests to fail.
 */
public class UserServiceWithDifferentValues {

    private final Repository<User> userRepository;
    private final Repository<Customer> customerRepository;

    public UserServiceWithDifferentValues(Repository<User> userRepository,
                                          Repository<Customer> customerRepository) {
        this.userRepository = userRepository;
        this.customerRepository = customerRepository;
    }

    /**
     * Process user John - should use "John" and "john@example.com"
     */
    public String processJohn() {
        User user = new User();
        user.setName("John");
        user.setEmail("john@example.com");
        user.setActive(true);
        user.setRole("user");
        userRepository.save(user);
        return user.getName();
    }

    /**
     * Process user Jane - should use "Jane" and "jane@example.com"
     */
    public String processJane() {
        User user = new User();
        user.setName("Jane");
        user.setEmail("jane@example.com");
        user.setActive(true);
        user.setRole("user");
        userRepository.save(user);
        return user.getName();
    }

    /**
     * Process admin user - should use "Admin" and "admin@example.com"
     */
    public String processAdmin() {
        User user = new User();
        user.setName("Admin");
        user.setEmail("admin@example.com");
        user.setActive(true);
        user.setRole("admin");
        userRepository.save(user);
        return user.getName();
    }

    /**
     * Process customer Bob - should use "Bob" and "bob@customer.com"
     */
    public String processCustomerBob() {
        Customer customer = new Customer();
        customer.setName("Bob");
        customer.setEmail("bob@customer.com");
        customer.setActive(true);
        customer.setRole("customer");
        customerRepository.save(customer);
        return customer.getName();
    }

    /**
     * Process customer Alice - should use "Alice" and "alice@customer.com"
     */
    public String processCustomerAlice() {
        Customer customer = new Customer();
        customer.setName("Alice");
        customer.setEmail("alice@customer.com");
        customer.setActive(false);
        customer.setRole("premium");
        customerRepository.save(customer);
        return customer.getName();
    }
}
