-- V1__initial_schema_test.sql
-- Test schema for ODK Platform (H2 compatible)

-- Users table
CREATE TABLE users (
    id UUID PRIMARY KEY,
    username VARCHAR(255) NOT NULL UNIQUE,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    first_name VARCHAR(100),
    last_name VARCHAR(100),
    phone_number VARCHAR(30),
    avatar_url VARCHAR(500),
    is_active BOOLEAN NOT NULL DEFAULT true,
    is_verified BOOLEAN NOT NULL DEFAULT false,
    last_login_at TIMESTAMP WITH TIME ZONE,
    password_changed_at TIMESTAMP WITH TIME ZONE,
    failed_login_attempts INTEGER NOT NULL DEFAULT 0,
    locked_until TIMESTAMP WITH TIME ZONE,
    preferred_language VARCHAR(10) DEFAULT 'en',
    timezone VARCHAR(50) DEFAULT 'UTC',
    metadata CLOB,
    is_superuser BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by UUID,
    updated_by UUID,
    CONSTRAINT fk_users_created_by FOREIGN KEY (created_by) REFERENCES users(id),
    CONSTRAINT fk_users_updated_by FOREIGN KEY (updated_by) REFERENCES users(id)
);


-- Projects table
CREATE TABLE projects (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    owner_id UUID NOT NULL REFERENCES users(id),
    created_by_id UUID NOT NULL REFERENCES users(id),
    updated_by_id UUID REFERENCES users(id),
    settings CLOB,
    metadata CLOB
);

-- Roles table
CREATE TABLE roles (
    id UUID PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE,
    display_name VARCHAR(255),
    description TEXT,
    is_system BOOLEAN NOT NULL DEFAULT false,
    is_active BOOLEAN NOT NULL DEFAULT true,
    metadata CLOB,
    project_id UUID REFERENCES projects(id) ON DELETE SET NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Permissions table
CREATE TABLE permissions (
    id UUID PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE,
    display_name VARCHAR(255),
    description TEXT,
    resource VARCHAR(100) NOT NULL,
    action VARCHAR(50) NOT NULL,
    is_system BOOLEAN NOT NULL DEFAULT false,
    is_active BOOLEAN NOT NULL DEFAULT true,
    metadata CLOB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Role permissions junction table
CREATE TABLE role_permissions (
    role_id UUID NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    permission_id UUID NOT NULL REFERENCES permissions(id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_id)
);

-- User roles junction table
CREATE TABLE user_roles (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_id UUID NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    project_id UUID REFERENCES projects(id) ON DELETE CASCADE,
    assigned_by UUID REFERENCES users(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (user_id, role_id, project_id)
);

-- Project members (users assigned to projects with roles)
CREATE TABLE project_members (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_id UUID NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    assigned_by UUID REFERENCES users(id),
    is_active BOOLEAN NOT NULL DEFAULT true,
    metadata CLOB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (project_id, user_id, role_id)
);

-- App users (mobile app users like ODK Collect users)
CREATE TABLE app_users (
    id UUID PRIMARY KEY,
    username VARCHAR(255) NOT NULL UNIQUE,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    full_name VARCHAR(255),
    phone_number VARCHAR(30),
    avatar_url VARCHAR(500),
    is_active BOOLEAN NOT NULL DEFAULT true,
    is_verified BOOLEAN NOT NULL DEFAULT false,
    last_login_at TIMESTAMP WITH TIME ZONE,
    password_changed_at TIMESTAMP WITH TIME ZONE,
    failed_login_attempts INTEGER NOT NULL DEFAULT 0,
    locked_until TIMESTAMP WITH TIME ZONE,
    preferred_language VARCHAR(10) DEFAULT 'en',
    timezone VARCHAR(50) DEFAULT 'UTC',
    metadata CLOB,
    last_sync_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by UUID REFERENCES users(id),
    updated_by UUID REFERENCES users(id)
);

-- Refresh tokens
-- Matches org.odk.platform.domain.RefreshToken.
CREATE TABLE refresh_tokens (
    id UUID PRIMARY KEY,
    token VARCHAR(500) NOT NULL UNIQUE,

    -- A refresh token can belong to either a web User or an AppUser.
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    app_user_id UUID REFERENCES app_users(id) ON DELETE CASCADE,

    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,

    revoked BOOLEAN NOT NULL DEFAULT false,
    revoked_at TIMESTAMP WITH TIME ZONE,
    replaced_by_token VARCHAR(500),

    ip_address VARCHAR(45),
    user_agent VARCHAR(500),
    metadata JSON,

    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX idx_refresh_tokens_token
    ON refresh_tokens(token);
CREATE INDEX idx_refresh_tokens_user_id
    ON refresh_tokens(user_id);

CREATE INDEX idx_refresh_tokens_app_user_id
    ON refresh_tokens(app_user_id);

CREATE INDEX idx_refresh_tokens_expires_at
    ON refresh_tokens(expires_at);

CREATE INDEX idx_refresh_tokens_revoked
    ON refresh_tokens(revoked);


-- Email verification tokens (matches EmailVerificationToken entity)
CREATE TABLE email_verification_tokens (
    id UUID PRIMARY KEY,
    token VARCHAR(500) NOT NULL UNIQUE,
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    app_user_id UUID REFERENCES app_users(id) ON DELETE CASCADE,
    email VARCHAR(255) NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    used BOOLEAN NOT NULL DEFAULT false,
    used_at TIMESTAMP WITH TIME ZONE,
    ip_address VARCHAR(45),
    user_agent VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX idx_email_verification_tokens_token
    ON email_verification_tokens(token);
CREATE INDEX idx_email_verification_tokens_user_id
    ON email_verification_tokens(user_id);
CREATE INDEX idx_email_verification_tokens_app_user_id
    ON email_verification_tokens(app_user_id);
CREATE INDEX idx_email_verification_tokens_expires_at
    ON email_verification_tokens(expires_at);
CREATE INDEX idx_email_verification_tokens_used
    ON email_verification_tokens(used);

-- Password reset tokens (matches PasswordResetToken entity)
CREATE TABLE password_reset_tokens (
    id UUID PRIMARY KEY,
    token VARCHAR(500) NOT NULL UNIQUE,
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    app_user_id UUID REFERENCES app_users(id) ON DELETE CASCADE,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    used BOOLEAN NOT NULL DEFAULT false,
    used_at TIMESTAMP WITH TIME ZONE,
    ip_address VARCHAR(45),
    user_agent VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX idx_password_reset_tokens_token
    ON password_reset_tokens(token);
CREATE INDEX idx_password_reset_tokens_user_id
    ON password_reset_tokens(user_id);
CREATE INDEX idx_password_reset_tokens_app_user_id
    ON password_reset_tokens(app_user_id);
CREATE INDEX idx_password_reset_tokens_expires_at
    ON password_reset_tokens(expires_at);
CREATE INDEX idx_password_reset_tokens_used
    ON password_reset_tokens(used);

-- XForms table (matches XForm entity)
CREATE TABLE xforms (
    id UUID PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    description TEXT,
    xmlns VARCHAR(500),
    version VARCHAR(50),
    xml_content TEXT,
    xls_content TEXT,
    is_published BOOLEAN NOT NULL DEFAULT false,
    is_active BOOLEAN NOT NULL DEFAULT true,
    is_encrypted BOOLEAN NOT NULL DEFAULT false,
    encryption_key VARCHAR(500),
    settings CLOB DEFAULT '{}',
    metadata CLOB DEFAULT '{}',
    published_at TIMESTAMP WITH TIME ZONE,
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    created_by_id UUID NOT NULL REFERENCES users(id),
    updated_by_id UUID REFERENCES users(id),
    published_by_id UUID REFERENCES users(id),
    current_version_id UUID,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- App user project assignments
CREATE TABLE app_user_projects (
    id UUID PRIMARY KEY,
    app_user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    form_id UUID REFERENCES xforms(id) ON DELETE SET NULL,
    assigned_by UUID REFERENCES users(id),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    metadata CLOB DEFAULT '{}',
    assigned_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (app_user_id, project_id, form_id)
);

-- Form versions table (for version history)
CREATE TABLE form_versions (
    id UUID PRIMARY KEY,
    form_id UUID NOT NULL REFERENCES xforms(id) ON DELETE CASCADE,
    version VARCHAR(50) NOT NULL,
    xml_content TEXT NOT NULL,
    xls_content TEXT,
    xmlns VARCHAR(500),
    form_hash VARCHAR(64),
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    is_published BOOLEAN NOT NULL DEFAULT false,
    is_latest BOOLEAN NOT NULL DEFAULT false,
    changelog TEXT,
    settings CLOB DEFAULT '{}',
    metadata CLOB DEFAULT '{}',
    published_at TIMESTAMP WITH TIME ZONE,
    validated_at TIMESTAMP WITH TIME ZONE,
    deprecated_at TIMESTAMP WITH TIME ZONE,
    archived_at TIMESTAMP WITH TIME ZONE,
    published_by UUID REFERENCES users(id),
    validated_by_id UUID REFERENCES users(id),
    deprecated_by_id UUID REFERENCES users(id),
    archived_by_id UUID REFERENCES users(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by UUID REFERENCES users(id),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by_id UUID REFERENCES users(id),
    published_by_id UUID REFERENCES users(id),
    CONSTRAINT chk_form_versions_status
        CHECK (status IN ('DRAFT', 'VALIDATED', 'PUBLISHED', 'DEPRECATED', 'ARCHIVED')),
    UNIQUE (form_id, version)
);

-- xforms.current_version_id references form_versions, so add the FK only
-- after form_versions exists to avoid the circular creation dependency.
ALTER TABLE xforms
    ADD CONSTRAINT fk_xforms_current_version
    FOREIGN KEY (current_version_id)
    REFERENCES form_versions(id);

CREATE INDEX idx_xforms_current_version_id
    ON xforms(current_version_id);

CREATE INDEX idx_form_versions_status
    ON form_versions(status);

CREATE INDEX idx_form_versions_validated_by
    ON form_versions(validated_by_id);

CREATE INDEX idx_form_versions_deprecated_by
    ON form_versions(deprecated_by_id);

CREATE INDEX idx_form_versions_archived_by
    ON form_versions(archived_by_id);

CREATE INDEX idx_form_versions_validated_at
    ON form_versions(validated_at);

CREATE INDEX idx_form_versions_deprecated_at
    ON form_versions(deprecated_at);

CREATE INDEX idx_form_versions_archived_at
    ON form_versions(archived_at);

CREATE INDEX idx_form_versions_form_hash
    ON form_versions(form_hash);

-- Form attachments/media table (production V4 equivalent for H2 tests)
CREATE TABLE form_attachments (
    id UUID PRIMARY KEY,
    form_version_id UUID NOT NULL REFERENCES form_versions(id) ON DELETE CASCADE,
    file_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(255),
    file_size BIGINT,
    storage_path VARCHAR(500) NOT NULL,
    storage_driver VARCHAR(50) NOT NULL DEFAULT 'local',
    content_hash VARCHAR(64),
    is_encrypted BOOLEAN NOT NULL DEFAULT false,
    encryption_key VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by_id UUID REFERENCES users(id),
    updated_by_id UUID REFERENCES users(id),
    CONSTRAINT uq_form_attachments_form_version_file
        UNIQUE (form_version_id, file_name)
);

CREATE INDEX idx_form_attachments_form_version_id
    ON form_attachments(form_version_id);

CREATE INDEX idx_form_attachments_content_hash
    ON form_attachments(content_hash);

CREATE INDEX idx_form_attachments_created_at
    ON form_attachments(created_at);

-- Form external datasets table (production V5 equivalent for H2 tests)
CREATE TABLE form_external_datasets (
    id UUID PRIMARY KEY,
    form_version_id UUID NOT NULL REFERENCES form_versions(id) ON DELETE CASCADE,
    dataset_id VARCHAR(255) NOT NULL,
    title VARCHAR(255) NOT NULL,
    description TEXT,
    content_type VARCHAR(100) NOT NULL,
    storage_path VARCHAR(500) NOT NULL,
    storage_driver VARCHAR(50) NOT NULL DEFAULT 'local',
    content_hash VARCHAR(64),
    file_size BIGINT,
    row_count BIGINT,
    column_definitions CLOB,
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by UUID REFERENCES users(id),
    updated_by UUID REFERENCES users(id),
    UNIQUE (form_version_id, dataset_id)
);

CREATE INDEX idx_form_external_datasets_form_version_id
    ON form_external_datasets(form_version_id);

CREATE INDEX idx_form_external_datasets_dataset_id
    ON form_external_datasets(dataset_id);

CREATE INDEX idx_form_external_datasets_content_hash
    ON form_external_datasets(content_hash);

CREATE INDEX idx_form_external_datasets_is_active
    ON form_external_datasets(is_active);

CREATE INDEX idx_form_external_datasets_created_at
    ON form_external_datasets(created_at);

-- Form publish lifecycle history (production V6 equivalent for H2 tests)
CREATE TABLE form_publish_history (
    id UUID PRIMARY KEY,
    form_version_id UUID NOT NULL REFERENCES form_versions(id) ON DELETE CASCADE,
    action VARCHAR(50) NOT NULL,
    performed_by UUID NOT NULL REFERENCES users(id),
    performed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    notes TEXT,
    previous_version_id UUID REFERENCES form_versions(id),
    CONSTRAINT chk_form_publish_history_action
        CHECK (action IN ('PUBLISHED', 'DEPRECATED', 'ARCHIVED', 'REPUBLISHED'))
);

CREATE INDEX idx_form_publish_history_form_version
    ON form_publish_history(form_version_id);

CREATE INDEX idx_form_publish_history_performed_by
    ON form_publish_history(performed_by);

CREATE INDEX idx_form_publish_history_performed_at
    ON form_publish_history(performed_at);

CREATE INDEX idx_form_publish_history_action
    ON form_publish_history(action);

-- Submissions table
CREATE TABLE submissions (
    id UUID PRIMARY KEY,
    form_id UUID NOT NULL REFERENCES xforms(id) ON DELETE CASCADE,
    form_version_id UUID REFERENCES form_versions(id) ON DELETE SET NULL,
    instance_id VARCHAR(255) NOT NULL,
    xml_content TEXT NOT NULL,
    json_content CLOB,
    status VARCHAR(20) NOT NULL DEFAULT 'SUBMITTED',
    submitted_at TIMESTAMP WITH TIME ZONE NOT NULL,
    reviewed_at TIMESTAMP WITH TIME ZONE,
    reviewed_by_id UUID REFERENCES users(id),
    review_notes TEXT,
    metadata CLOB DEFAULT '{}',
    attachments CLOB DEFAULT '[]',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    submitted_by_id UUID REFERENCES app_users(id),
    UNIQUE (form_id, instance_id)
);

-- Submission attachments
CREATE TABLE submission_attachments (
    id UUID PRIMARY KEY,
    submission_id UUID NOT NULL REFERENCES submissions(id) ON DELETE CASCADE,
    answer_id UUID,
    file_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(255),
    file_size BIGINT,
    storage_path VARCHAR(500) NOT NULL,
    storage_driver VARCHAR(50) NOT NULL DEFAULT 'local',
    content_hash VARCHAR(64),
    metadata CLOB DEFAULT '{}',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Project settings table
CREATE TABLE project_settings (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    setting_key VARCHAR(255) NOT NULL,
    setting_value CLOB,
    description TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by UUID REFERENCES users(id),
    updated_by UUID REFERENCES users(id),
    UNIQUE (project_id, setting_key)
);

-- Index for project settings
CREATE INDEX idx_project_settings_project_id ON project_settings(project_id);

-- Audit log table
CREATE TABLE audit_logs (
    id UUID PRIMARY KEY,
    action VARCHAR(100) NOT NULL,
    resource_type VARCHAR(100),
    resource_id VARCHAR(100),
    resource_name VARCHAR(255),
    old_values CLOB,
    new_values CLOB,
    ip_address VARCHAR(45),
    user_agent VARCHAR(500),
    request_id VARCHAR(100),
    session_id VARCHAR(100),
    status VARCHAR(20) NOT NULL DEFAULT 'SUCCESS',
    error_message CLOB,
    metadata CLOB,
    user_id UUID REFERENCES users(id),
    app_user_id UUID REFERENCES app_users(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_audit_logs_user_id ON audit_logs(user_id);
CREATE INDEX idx_audit_logs_app_user_id ON audit_logs(app_user_id);
CREATE INDEX idx_audit_logs_action ON audit_logs(action);
CREATE INDEX idx_audit_logs_resource_type ON audit_logs(resource_type);
CREATE INDEX idx_audit_logs_resource_id ON audit_logs(resource_id);
CREATE INDEX idx_audit_logs_created_at ON audit_logs(created_at);
CREATE INDEX idx_audit_logs_ip_address ON audit_logs(ip_address);

-- Structured audit event details (production V19 equivalent for H2 tests)
CREATE TABLE audit_log_details (
    id UUID DEFAULT RANDOM_UUID() PRIMARY KEY,
    audit_log_id UUID NOT NULL REFERENCES audit_logs(id) ON DELETE CASCADE,
    detail_key VARCHAR(255) NOT NULL,
    old_value CLOB,
    new_value CLOB,
    value_type VARCHAR(50),
    metadata CLOB NOT NULL DEFAULT '{}',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_audit_log_details_audit_log_id ON audit_log_details(audit_log_id);
CREATE INDEX idx_audit_log_details_detail_key ON audit_log_details(detail_key);
CREATE INDEX idx_audit_log_details_created_at ON audit_log_details(created_at);

-- =========================================================
-- SUBMISSION ANSWERS
-- Flattened answers for reporting and querying
-- =========================================================

CREATE TABLE submission_answers (
    id UUID PRIMARY KEY,
    submission_id UUID NOT NULL REFERENCES submissions(id) ON DELETE CASCADE,
    path VARCHAR(500) NOT NULL,
    field_name VARCHAR(255) NOT NULL,
    field_type VARCHAR(100),
    value_text TEXT,
    value_number NUMERIC,
    value_date TIMESTAMP WITH TIME ZONE,
    value_boolean BOOLEAN,
    value_json CLOB,
    repeat_path VARCHAR(500),
    repeat_index INTEGER,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE submission_attachments
    ADD CONSTRAINT fk_submission_attachments_answer
    FOREIGN KEY (answer_id)
    REFERENCES submission_answers(id)
    ON DELETE SET NULL;

-- =========================================================
-- SUBMISSION EVENTS
-- Submission lifecycle events for audit trail
-- =========================================================

CREATE TABLE submission_events (
    id UUID PRIMARY KEY,
    submission_id UUID NOT NULL REFERENCES submissions(id) ON DELETE CASCADE,
    event_type VARCHAR(50) NOT NULL,
    event_status VARCHAR(20) NOT NULL DEFAULT 'success',
    actor_type VARCHAR(50),
    actor_id UUID,
    details CLOB,
    error_message TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- =========================================================
-- SUBMISSION VERSIONS
-- Track submission replacements/updates for audit trail
-- =========================================================

CREATE TABLE submission_versions (
    id UUID PRIMARY KEY,
    submission_id UUID NOT NULL REFERENCES submissions(id) ON DELETE CASCADE,
    version_number INTEGER NOT NULL,
    xml_content TEXT NOT NULL,
    json_content CLOB,
    instance_id VARCHAR(255) NOT NULL,
    deprecated_id VARCHAR(255),
    status VARCHAR(20) NOT NULL DEFAULT 'SUBMITTED',
    metadata CLOB,
    submitted_by_id UUID REFERENCES app_users(id),
    submitted_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- =========================================================
-- INDEXES
-- =========================================================

CREATE INDEX idx_submission_answers_submission_id ON submission_answers(submission_id);
CREATE INDEX idx_submission_answers_path ON submission_answers(path);
CREATE INDEX idx_submission_answers_field_name ON submission_answers(field_name);
CREATE INDEX idx_submission_answers_repeat_path ON submission_answers(repeat_path);

CREATE INDEX idx_submission_events_submission_id ON submission_events(submission_id);
CREATE INDEX idx_submission_events_event_type ON submission_events(event_type);
CREATE INDEX idx_submission_events_created_at ON submission_events(created_at);

CREATE INDEX idx_submission_versions_submission_id ON submission_versions(submission_id);
CREATE INDEX idx_submission_versions_instance_id ON submission_versions(instance_id);
CREATE INDEX idx_submission_versions_deprecated_id ON submission_versions(deprecated_id);
CREATE INDEX idx_submission_versions_version_number ON submission_versions(submission_id, version_number);
CREATE INDEX idx_submission_versions_submitted_at ON submission_versions(submitted_at);

-- Ensure version numbers are unique per submission
ALTER TABLE submission_versions ADD CONSTRAINT uk_submission_versions_submission_version 
    UNIQUE (submission_id, version_number);

-- Indexes for performance
CREATE INDEX idx_users_username ON users(username);
CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_is_active ON users(is_active);
CREATE INDEX idx_projects_is_active ON projects(is_active);
CREATE INDEX idx_xforms_project_id ON xforms(project_id);
CREATE INDEX idx_xforms_xmlns ON xforms(xmlns);
CREATE INDEX idx_xforms_is_published ON xforms(is_published);
CREATE INDEX idx_form_versions_form_id ON form_versions(form_id);
CREATE INDEX idx_submissions_form_id ON submissions(form_id);
CREATE INDEX idx_submissions_instance_id ON submissions(instance_id);
CREATE INDEX idx_submissions_status ON submissions(status);
CREATE INDEX idx_submissions_submitted_at ON submissions(submitted_at);
CREATE INDEX idx_submissions_submitted_by ON submissions(submitted_by_id);
CREATE INDEX idx_submission_attachments_submission_id ON submission_attachments(submission_id);
CREATE INDEX idx_submission_attachments_answer_id ON submission_attachments(answer_id);
-- audit_logs indexes are defined next to the audit_logs table above.
-- Do not recreate the legacy actor_id/target_type/target_id indexes here: those
-- columns are not part of the current AuditLog entity/test schema.
CREATE INDEX idx_app_users_username ON app_users(username);
CREATE INDEX idx_app_user_projects_app_user_id ON app_user_projects(app_user_id);
CREATE INDEX idx_app_user_projects_project_id ON app_user_projects(project_id);
CREATE INDEX idx_project_members_project_id ON project_members(project_id);
CREATE INDEX idx_project_members_user_id ON project_members(user_id);

-- Insert default system roles
INSERT INTO roles (id, name, display_name, description, is_system, is_active) VALUES
    (RANDOM_UUID(), 'SYSTEM_ADMIN', 'System Administrator', 'System administrator with full access', true, true),
    (RANDOM_UUID(), 'PROJECT_ADMIN', 'Project Administrator', 'Project administrator', true, true),
    (RANDOM_UUID(), 'PROJECT_MANAGER', 'Project Manager', 'Project manager', true, true),
    (RANDOM_UUID(), 'DATA_ANALYST', 'Data Analyst', 'Data analyst with read access', true, true),
    (RANDOM_UUID(), 'DATA_COLLECTOR', 'Data Collector', 'Data collector / field worker', true, true),
    (RANDOM_UUID(), 'VIEWER', 'Viewer', 'Read-only viewer', true, true);

-- Insert default permissions
INSERT INTO permissions (id, name, description, resource, action) VALUES
    (RANDOM_UUID(), 'USER_CREATE', 'Create users', 'user', 'create'),
    (RANDOM_UUID(), 'USER_READ', 'Read users', 'user', 'read'),
    (RANDOM_UUID(), 'USER_UPDATE', 'Update users', 'user', 'update'),
    (RANDOM_UUID(), 'USER_DELETE', 'Delete users', 'user', 'delete'),
    (RANDOM_UUID(), 'PROJECT_CREATE', 'Create projects', 'project', 'create'),
    (RANDOM_UUID(), 'PROJECT_READ', 'Read projects', 'project', 'read'),
    (RANDOM_UUID(), 'PROJECT_UPDATE', 'Update projects', 'project', 'update'),
    (RANDOM_UUID(), 'PROJECT_DELETE', 'Delete projects', 'project', 'delete'),
    (RANDOM_UUID(), 'FORM_CREATE', 'Create forms', 'form', 'create'),
    (RANDOM_UUID(), 'FORM_READ', 'Read forms', 'form', 'read'),
    (RANDOM_UUID(), 'FORM_UPDATE', 'Update forms', 'form', 'update'),
    (RANDOM_UUID(), 'FORM_DELETE', 'Delete forms', 'form', 'delete'),
    (RANDOM_UUID(), 'FORM_PUBLISH', 'Publish forms', 'form', 'publish'),
    (RANDOM_UUID(), 'SUBMISSION_READ', 'Read submissions', 'submission', 'read'),
    (RANDOM_UUID(), 'SUBMISSION_UPDATE', 'Update submissions', 'submission', 'update'),
    (RANDOM_UUID(), 'SUBMISSION_DELETE', 'Delete submissions', 'submission', 'delete'),
    (RANDOM_UUID(), 'REPORT_CREATE', 'Create reports', 'report', 'create'),
    (RANDOM_UUID(), 'REPORT_READ', 'Read reports', 'report', 'read'),
    (RANDOM_UUID(), 'REPORT_EXPORT', 'Export reports', 'report', 'export'),
    (RANDOM_UUID(), 'AUDIT_READ', 'Read audit logs', 'audit', 'read'),
    (RANDOM_UUID(), 'APP_USER_CREATE', 'Create app users', 'app_user', 'create'),
    (RANDOM_UUID(), 'APP_USER_READ', 'Read app users', 'app_user', 'read'),
    (RANDOM_UUID(), 'APP_USER_UPDATE', 'Update app users', 'app_user', 'update'),
    (RANDOM_UUID(), 'APP_USER_DELETE', 'Delete app users', 'app_user', 'delete'),
    (RANDOM_UUID(), 'REFERENCE_DATA_CREATE', 'Create reference data', 'reference_data', 'create'),
    (RANDOM_UUID(), 'REFERENCE_DATA_READ', 'Read reference data', 'reference_data', 'read'),
    (RANDOM_UUID(), 'REFERENCE_DATA_UPDATE', 'Update reference data', 'reference_data', 'update'),
    (RANDOM_UUID(), 'REFERENCE_DATA_DELETE', 'Delete reference data', 'reference_data', 'delete');

-- Assign all permissions to SYSTEM_ADMIN role
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r, permissions p
WHERE r.name = 'SYSTEM_ADMIN';

-- Assign project-level permissions to PROJECT_ADMIN
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r, permissions p
WHERE r.name = 'PROJECT_ADMIN'
  AND p.name IN ('PROJECT_READ', 'PROJECT_UPDATE', 'FORM_CREATE', 'FORM_READ', 'FORM_UPDATE', 'FORM_DELETE', 'FORM_PUBLISH',
                 'SUBMISSION_READ', 'SUBMISSION_UPDATE', 'REPORT_CREATE', 'REPORT_READ', 'REPORT_EXPORT',
                 'APP_USER_CREATE', 'APP_USER_READ', 'APP_USER_UPDATE', 'APP_USER_DELETE',
                 'REFERENCE_DATA_CREATE', 'REFERENCE_DATA_READ', 'REFERENCE_DATA_UPDATE', 'REFERENCE_DATA_DELETE');

-- Assign read permissions to VIEWER
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r, permissions p
WHERE r.name = 'VIEWER'
  AND p.name IN ('PROJECT_READ', 'FORM_READ', 'SUBMISSION_READ', 'REPORT_READ', 'APP_USER_READ', 'REFERENCE_DATA_READ');

-- Assign data collector permissions
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r, permissions p
WHERE r.name = 'DATA_COLLECTOR'
  AND p.name IN ('FORM_READ', 'SUBMISSION_READ');

-- =========================================================
-- WEB FORM SESSIONS (from V12__web_form_sessions.sql - H2 compatible)
-- =========================================================

CREATE TABLE web_form_sessions (
    id UUID PRIMARY KEY,
    form_version_id UUID NOT NULL REFERENCES form_versions(id) ON DELETE CASCADE,
    user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    app_user_id UUID REFERENCES app_users(id) ON DELETE SET NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'draft',
    current_screen VARCHAR(255),
    current_question_path VARCHAR(500),
    session_data CLOB NOT NULL DEFAULT '{}',
    started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_activity_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP,
    submitted_at TIMESTAMP,
    submission_id UUID REFERENCES submissions(id) ON DELETE SET NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by UUID REFERENCES users(id),
    updated_by UUID REFERENCES users(id)
);

-- =========================================================
-- WEB FORM ANSWERS
-- =========================================================

CREATE TABLE web_form_answers (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES web_form_sessions(id) ON DELETE CASCADE,
    question_path VARCHAR(500) NOT NULL,
    instance_path VARCHAR(1000),
    question_name VARCHAR(255),
    question_type VARCHAR(100),
    value_text TEXT,
    value_number NUMERIC,
    value_date TIMESTAMP,
    value_boolean BOOLEAN,
    value_json CLOB,
    repeat_path VARCHAR(500),
    repeat_index INTEGER,
    is_relevant BOOLEAN NOT NULL DEFAULT true,
    is_required BOOLEAN NOT NULL DEFAULT false,
    constraint_message TEXT,
    validation_status VARCHAR(50) NOT NULL DEFAULT 'pending',
    answered_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- =========================================================
-- WEB FORM ATTACHMENTS
-- =========================================================

CREATE TABLE web_form_attachments (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES web_form_sessions(id) ON DELETE CASCADE,
    answer_id UUID REFERENCES web_form_answers(id) ON DELETE SET NULL,
    question_path VARCHAR(500),
    file_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(255),
    file_size BIGINT,
    storage_path VARCHAR(500) NOT NULL,
    storage_driver VARCHAR(50) NOT NULL DEFAULT 'local',
    content_hash VARCHAR(64),
    metadata CLOB NOT NULL DEFAULT '{}',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- =========================================================
-- INDEXES FOR WEB FORM TABLES
-- =========================================================

CREATE INDEX idx_web_form_sessions_form_version_id ON web_form_sessions(form_version_id);
CREATE INDEX idx_web_form_sessions_user_id ON web_form_sessions(user_id);
CREATE INDEX idx_web_form_sessions_app_user_id ON web_form_sessions(app_user_id);
CREATE INDEX idx_web_form_sessions_status ON web_form_sessions(status);
CREATE INDEX idx_web_form_sessions_started_at ON web_form_sessions(started_at);
CREATE INDEX idx_web_form_sessions_last_activity_at ON web_form_sessions(last_activity_at);
CREATE INDEX idx_web_form_sessions_submission_id ON web_form_sessions(submission_id);

CREATE INDEX idx_web_form_answers_session_id ON web_form_answers(session_id);
CREATE INDEX idx_web_form_answers_question_path ON web_form_answers(question_path);
CREATE INDEX idx_web_form_answers_instance_path ON web_form_answers(instance_path);
CREATE INDEX idx_web_form_answers_repeat_path ON web_form_answers(repeat_path);
CREATE INDEX idx_web_form_answers_validation_status ON web_form_answers(validation_status);

CREATE INDEX idx_web_form_attachments_session_id ON web_form_attachments(session_id);
CREATE INDEX idx_web_form_attachments_answer_id ON web_form_attachments(answer_id);

-- Generic Entity List lookup foundation (production V16 equivalent for H2 tests)
CREATE TABLE entity_lists (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    display_name VARCHAR(255),
    description TEXT,
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (project_id, name)
);

CREATE INDEX idx_entity_lists_project_id ON entity_lists(project_id);
CREATE INDEX idx_entity_lists_name ON entity_lists(name);
CREATE INDEX idx_entity_lists_is_active ON entity_lists(is_active);

CREATE TABLE entity_items (
    id UUID PRIMARY KEY,
    entity_list_id UUID NOT NULL REFERENCES entity_lists(id) ON DELETE CASCADE,
    entity_id VARCHAR(255) NOT NULL,
    label CLOB NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (entity_list_id, entity_id)
);

CREATE INDEX idx_entity_items_entity_list_id ON entity_items(entity_list_id);
CREATE INDEX idx_entity_items_entity_id ON entity_items(entity_id);
CREATE INDEX idx_entity_items_is_active ON entity_items(is_active);

CREATE TABLE entity_item_properties (
    id UUID PRIMARY KEY,
    entity_item_id UUID NOT NULL REFERENCES entity_items(id) ON DELETE CASCADE,
    property_name VARCHAR(255) NOT NULL,
    property_value CLOB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (entity_item_id, property_name)
);

CREATE INDEX idx_entity_item_properties_item_id ON entity_item_properties(entity_item_id);
CREATE INDEX idx_entity_item_properties_name ON entity_item_properties(property_name);

-- V17 generic reference data (test schema mirror)
CREATE TABLE reference_lists (
    id UUID DEFAULT RANDOM_UUID() PRIMARY KEY,
    project_id UUID NOT NULL,
    name VARCHAR(255) NOT NULL,
    display_name VARCHAR(255),
    description TEXT,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_reference_lists_project FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE,
    CONSTRAINT uk_reference_lists_project_name UNIQUE (project_id, name)
);
CREATE INDEX idx_reference_lists_project_id ON reference_lists(project_id);
CREATE INDEX idx_reference_lists_name ON reference_lists(name);
CREATE INDEX idx_reference_lists_is_active ON reference_lists(is_active);

CREATE TABLE reference_items (
    id UUID DEFAULT RANDOM_UUID() PRIMARY KEY,
    reference_list_id UUID NOT NULL,
    item_key VARCHAR(255) NOT NULL,
    label TEXT NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_reference_items_list FOREIGN KEY (reference_list_id) REFERENCES reference_lists(id) ON DELETE CASCADE,
    CONSTRAINT uk_reference_items_list_key UNIQUE (reference_list_id, item_key)
);
CREATE INDEX idx_reference_items_list_id ON reference_items(reference_list_id);
CREATE INDEX idx_reference_items_item_key ON reference_items(item_key);
CREATE INDEX idx_reference_items_is_active ON reference_items(is_active);

CREATE TABLE reference_item_properties (
    id UUID DEFAULT RANDOM_UUID() PRIMARY KEY,
    reference_item_id UUID NOT NULL,
    property_name VARCHAR(255) NOT NULL,
    property_value TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_reference_item_properties_item FOREIGN KEY (reference_item_id) REFERENCES reference_items(id) ON DELETE CASCADE,
    CONSTRAINT uk_reference_item_properties_item_name UNIQUE (reference_item_id, property_name)
);
CREATE INDEX idx_reference_item_properties_item_id ON reference_item_properties(reference_item_id);
CREATE INDEX idx_reference_item_properties_name ON reference_item_properties(property_name);

CREATE TABLE reference_item_relationships (
    id UUID DEFAULT RANDOM_UUID() PRIMARY KEY,
    parent_item_id UUID NOT NULL,
    child_item_id UUID NOT NULL,
    relationship_type VARCHAR(100) NOT NULL DEFAULT 'parent',
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_reference_relationship_parent FOREIGN KEY (parent_item_id) REFERENCES reference_items(id) ON DELETE CASCADE,
    CONSTRAINT fk_reference_relationship_child FOREIGN KEY (child_item_id) REFERENCES reference_items(id) ON DELETE CASCADE,
    CONSTRAINT chk_reference_relationship_not_self CHECK (parent_item_id <> child_item_id),
    CONSTRAINT uk_reference_relationship UNIQUE (parent_item_id, child_item_id, relationship_type)
);
CREATE INDEX idx_reference_relationships_parent ON reference_item_relationships(parent_item_id);
CREATE INDEX idx_reference_relationships_child ON reference_item_relationships(child_item_id);
CREATE INDEX idx_reference_relationships_type ON reference_item_relationships(relationship_type);

-- =========================================================
-- GENERIC REPORTING (production V18 equivalent for H2 tests)
-- =========================================================
CREATE TABLE report_definitions (
    id UUID DEFAULT RANDOM_UUID() PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    form_id UUID NOT NULL REFERENCES xforms(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    report_type VARCHAR(30) NOT NULL DEFAULT 'TABLE',
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_by_id UUID REFERENCES users(id) ON DELETE SET NULL,
    updated_by_id UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_report_definitions_type CHECK (report_type IN ('TABLE', 'SUMMARY')),
    CONSTRAINT uk_report_definitions_project_name UNIQUE (project_id, name)
);

CREATE TABLE report_fields (
    id UUID DEFAULT RANDOM_UUID() PRIMARY KEY,
    report_definition_id UUID NOT NULL REFERENCES report_definitions(id) ON DELETE CASCADE,
    field_path VARCHAR(500) NOT NULL,
    field_kind VARCHAR(30) NOT NULL DEFAULT 'ANSWER',
    label VARCHAR(255),
    data_type VARCHAR(100),
    aggregation VARCHAR(20) NOT NULL DEFAULT 'NONE',
    group_by BOOLEAN NOT NULL DEFAULT FALSE,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_report_fields_kind CHECK (field_kind IN ('ANSWER', 'SYSTEM')),
    CONSTRAINT chk_report_fields_aggregation CHECK (aggregation IN ('NONE', 'COUNT', 'SUM', 'AVG', 'MIN', 'MAX'))
);

CREATE TABLE report_filters (
    id UUID DEFAULT RANDOM_UUID() PRIMARY KEY,
    report_definition_id UUID NOT NULL REFERENCES report_definitions(id) ON DELETE CASCADE,
    field_path VARCHAR(500) NOT NULL,
    field_kind VARCHAR(30) NOT NULL DEFAULT 'ANSWER',
    operator VARCHAR(30) NOT NULL,
    value_text TEXT,
    value_to TEXT,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_report_filters_kind CHECK (field_kind IN ('ANSWER', 'SYSTEM')),
    CONSTRAINT chk_report_filters_operator CHECK (operator IN ('EQ','NE','CONTAINS','GT','GTE','LT','LTE','IN','BETWEEN','IS_EMPTY','NOT_EMPTY'))
);

CREATE TABLE report_runs (
    id UUID DEFAULT RANDOM_UUID() PRIMARY KEY,
    report_definition_id UUID NOT NULL REFERENCES report_definitions(id) ON DELETE CASCADE,
    run_by_id UUID REFERENCES users(id) ON DELETE SET NULL,
    parameters CLOB,
    row_count INTEGER NOT NULL DEFAULT 0,
    source_submission_count INTEGER NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'RUNNING',
    duration_ms BIGINT,
    error_message TEXT,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT chk_report_runs_status CHECK (status IN ('RUNNING','COMPLETED','FAILED'))
);

CREATE TABLE report_exports (
    id UUID DEFAULT RANDOM_UUID() PRIMARY KEY,
    report_run_id UUID NOT NULL REFERENCES report_runs(id) ON DELETE CASCADE,
    format VARCHAR(20) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(255) NOT NULL,
    file_size BIGINT NOT NULL,
    created_by_id UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_report_exports_format CHECK (format IN ('CSV','XLSX'))
);

CREATE TABLE dashboard_widgets (
    id UUID DEFAULT RANDOM_UUID() PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    report_definition_id UUID REFERENCES report_definitions(id) ON DELETE CASCADE,
    title VARCHAR(255) NOT NULL,
    widget_type VARCHAR(30) NOT NULL DEFAULT 'TABLE',
    config CLOB,
    sort_order INTEGER NOT NULL DEFAULT 0,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_dashboard_widgets_type CHECK (widget_type IN ('TABLE','NUMBER','BAR','LINE','PIE'))
);

CREATE INDEX idx_report_definitions_project_id ON report_definitions(project_id);
CREATE INDEX idx_report_definitions_form_id ON report_definitions(form_id);
CREATE INDEX idx_report_fields_definition_id ON report_fields(report_definition_id);
CREATE INDEX idx_report_filters_definition_id ON report_filters(report_definition_id);
CREATE INDEX idx_report_runs_definition_id ON report_runs(report_definition_id);
CREATE INDEX idx_report_runs_started_at ON report_runs(started_at);
CREATE INDEX idx_report_exports_run_id ON report_exports(report_run_id);
CREATE INDEX idx_dashboard_widgets_project_id ON dashboard_widgets(project_id);
CREATE INDEX idx_dashboard_widgets_report_id ON dashboard_widgets(report_definition_id);

-- Web/browser form assignments (production V21 equivalent for H2 tests)
CREATE TABLE web_form_assignments (
    id UUID DEFAULT RANDOM_UUID() PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    form_id UUID NOT NULL REFERENCES xforms(id) ON DELETE CASCADE,
    target_type VARCHAR(20) NOT NULL,
    target_id UUID NOT NULL,
    assigned_by UUID REFERENCES users(id) ON DELETE SET NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    assigned_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_web_form_assignments_target_type
        CHECK (target_type IN ('USER', 'ROLE')),
    CONSTRAINT uq_web_form_assignments_target
        UNIQUE (project_id, form_id, target_type, target_id)
);

CREATE INDEX idx_web_form_assignments_project_form
    ON web_form_assignments(project_id, form_id);
CREATE INDEX idx_web_form_assignments_target
    ON web_form_assignments(target_type, target_id);
CREATE INDEX idx_web_form_assignments_active
    ON web_form_assignments(project_id, form_id, is_active);

