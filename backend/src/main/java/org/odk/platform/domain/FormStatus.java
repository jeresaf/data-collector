package org.odk.platform.domain;

public enum FormStatus {
    DRAFT("draft"),
    VALIDATED("validated"),
    PUBLISHED("published"),
    DEPRECATED("deprecated"),
    ARCHIVED("archived");

    private final String value;

    FormStatus(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static FormStatus fromValue(String value) {
        for (FormStatus status : FormStatus.values()) {
            if (status.value.equalsIgnoreCase(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown form status: " + value);
    }
}