package com.serverarchitecture;

public class User {
    protected String userId;
    protected String name;
    protected Role role;
    protected String passwordHash;

    public User(String userId, String name, Role role, String passwordHash) {
        this.userId = userId;
        this.name = name;
        this.role = role;
        this.passwordHash = passwordHash;
    }

    public String getUserId() {
        return this.userId;
    }

    public String getName() {
        return this.name;
    }

    public Role getRole() {
        return this.role;
    }

    public String getPasswordHash() {
        return this.passwordHash;
    }
}
