package com.raditha.bertie.testbed.model;

import java.time.LocalDateTime;

/**
 * User domain model for test bed.
 * This is a MUTABLE class to expose Gap 6 (test isolation issues).
 */
public class User {
    private String id;
    private String name;
    private String email;
    private boolean active;
    private String role;
    private LocalDateTime lastLogin;
    private int age;
    private int loginCount = 0;

    public User() {
    }

    public User(String name) {
        this.name = name;
    }

    public User(String name, String email) {
        this.name = name;
        this.email = email;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public LocalDateTime getLastLogin() {
        return lastLogin;
    }

    public void setLastLogin(LocalDateTime lastLogin) {
        this.lastLogin = lastLogin;
    }

    public void save() {
        // Simulated save operation
    }

    public int getStatus() {
        return active ? 200 : 404;
    }

    public int getAge() {
        return age;
    }

    public void setAge(int age) {
        this.age = age;
    }

    public int getLoginCount() {
        return loginCount;
    }

    public void incrementLoginCount() {
        this.loginCount++;
    }

    @Override
    public String toString() {
        return "User{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", email='" + email + '\'' +
                ", active=" + active +
                ", role='" + role + '\'' +
                ", age=" + age +
                ", loginCount=" + loginCount +
                '}';
    }
}
