package org.example.eshop.commerce.account;

public record AccountPasswordRequest(String currentPassword, String newPassword, String confirmPassword) {}
