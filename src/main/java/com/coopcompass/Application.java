package com.coopcompass;

import java.time.Instant;
import java.util.List;

public record Application(
        long id,
        String company,
        String role,
        String location,
        String source,
        Status status,
        String deadline,
        String notes,
        List<String> skills,
        Instant createdAt
) {
    public enum Status {
        SAVED("Saved"), APPLIED("Applied"), INTERVIEW("Interview"), OFFER("Offer"), REJECTED("Rejected");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public static Status from(String value) {
            if (value == null || value.isBlank()) return SAVED;
            for (Status status : values()) {
                if (status.name().equalsIgnoreCase(value) || status.label.equalsIgnoreCase(value)) return status;
            }
            throw new IllegalArgumentException("Choose a valid application status.");
        }
    }

    public Application withStatus(Status nextStatus) {
        return new Application(id, company, role, location, source, nextStatus, deadline, notes, skills, createdAt);
    }
}
