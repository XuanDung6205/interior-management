package org.example.eshop.commerce.inventory;

public record ShipmentScheduleRequest(String kind, String scheduledFrom, String scheduledTo,
        String installationAt) {}
