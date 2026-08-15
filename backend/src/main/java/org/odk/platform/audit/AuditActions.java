package org.odk.platform.audit;

/**
 * Stable action names for security/administrative audit events.
 * Existing action names are preserved so older audit rows remain meaningful.
 */
public final class AuditActions {
    private AuditActions() {}

    public static final String LOGIN_SUCCESS = "LOGIN_SUCCESS";
    public static final String LOGIN_FAILURE = "LOGIN_FAILURE";
    public static final String LOGOUT = "LOGOUT";

    public static final String USER_CREATED = "USER_CREATED";
    public static final String USER_UPDATED = "USER_UPDATED";
    public static final String USER_STATUS_CHANGED = "USER_STATUS_CHANGED";
    public static final String USER_ROLE_CHANGED = "USER_ROLE_CHANGED";

    public static final String APP_USER_CREATED = "APP_USER_CREATED";
    public static final String APP_USER_UPDATED = "APP_USER_UPDATED";
    public static final String APP_USER_STATUS_CHANGED = "APP_USER_STATUS_CHANGED";

    public static final String PROJECT_CREATED = "PROJECT_CREATED";
    public static final String PROJECT_UPDATED = "PROJECT_UPDATED";
    public static final String PROJECT_ARCHIVED = "PROJECT_ARCHIVED";
    public static final String FORM_UPLOADED = "FORM_UPLOADED";
    public static final String FORM_VALIDATED = "FORM_VALIDATED";
    public static final String FORM_PUBLISHED = "FORM_PUBLISHED";
    public static final String FORM_ASSIGNED = "FORM_ASSIGNED";
    public static final String FORM_UNASSIGNED = "FORM_UNASSIGNED";
    public static final String OPENROSA_FORM_DOWNLOADED = "OPENROSA_FORM_DOWNLOADED";
    public static final String SUBMISSION_RECEIVED = "SUBMISSION_RECEIVED";
    public static final String SUBMISSION_DUPLICATE = "SUBMISSION_DUPLICATE";
    public static final String SUBMISSION_REPLACED = "SUBMISSION_REPLACED";
    public static final String SUBMISSION_STATUS_CHANGED = "SUBMISSION_STATUS_CHANGED";
    public static final String REPORT_CREATED = "REPORT_CREATED";
    public static final String REPORT_UPDATED = "REPORT_UPDATED";
    public static final String REPORT_DELETED = "REPORT_DELETED";
    public static final String REPORT_EXPORTED = "REPORT_EXPORTED";
    public static final String REFERENCE_DATA_CHANGED = "REFERENCE_DATA_CHANGED";
    public static final String PROJECT_SETTINGS_UPDATED = "PROJECT_SETTINGS_UPDATED";
}
