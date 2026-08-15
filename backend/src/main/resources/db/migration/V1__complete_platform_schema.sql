-- V1__complete_platform_schema.sql
-- SQUASHED POSTGRESQL BASELINE FOR ODK PLATFORM
--
-- This file consolidates the previous V1 through V22 migrations into one
-- fresh-database baseline. It preserves their execution order and behavior,
-- while folding known PostgreSQL/JPA alignment fixes directly into the baseline.
--
-- IMPORTANT:
--   * Use this file for a NEW/CLEAN database only.
--   * Do not replace an already-applied Flyway history in an existing database.
--   * When using this baseline, remove the old V1..V22 files from db/migration.
--
-- Squash adjustments applied directly:
--   1. refresh_tokens.token VARCHAR(500) (matches RefreshToken entity)
--   2. refresh_tokens.replaced_by_token VARCHAR(500)
--   3. refresh_tokens.ip_address VARCHAR(45) instead of PostgreSQL INET
--   4. refresh_tokens.user_agent VARCHAR(500) instead of TEXT
--   5. audit_logs.ip_address VARCHAR(45) instead of PostgreSQL INET
--   6. audit_logs.user_agent VARCHAR(500) instead of TEXT
--   7. web-form assignment migration is retained as V22 semantics, avoiding the
--      historical duplicate-V21 migration version collision.
--
-- The prior V23 audit string-column alignment migration is not needed on a
-- clean database initialized from this squashed baseline because those types
-- are correct from table creation.


-- ============================================================================
-- CONSOLIDATED FROM: V1__initial_schema.sql
-- ============================================================================

-- V1__initial_schema.sql
-- Clean initial PostgreSQL schema for ODK Platform.
-- This migration is intentionally aligned with the current JPA/test schema.
-- V1-V7 have never been deployed, so the historical forms/xforms drift is removed here.

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- =========================================================
-- USERS
-- =========================================================

CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
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
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    is_superuser BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by UUID,
    updated_by UUID,
    CONSTRAINT fk_users_created_by
        FOREIGN KEY (created_by) REFERENCES users(id),
    CONSTRAINT fk_users_updated_by
        FOREIGN KEY (updated_by) REFERENCES users(id)
);

-- =========================================================
-- PROJECTS
-- Must exist before roles/user_roles because those may scope to projects.
-- =========================================================

CREATE TABLE projects (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(255) NOT NULL,
    description TEXT,
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    owner_id UUID NOT NULL REFERENCES users(id),
    created_by_id UUID NOT NULL REFERENCES users(id),
    updated_by_id UUID REFERENCES users(id),
    settings JSONB NOT NULL DEFAULT '{}'::jsonb,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb
);

-- =========================================================
-- ROLES / PERMISSIONS
-- =========================================================

CREATE TABLE roles (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(100) NOT NULL UNIQUE,
    display_name VARCHAR(255),
    description TEXT,
    is_system BOOLEAN NOT NULL DEFAULT false,
    is_active BOOLEAN NOT NULL DEFAULT true,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    project_id UUID REFERENCES projects(id) ON DELETE SET NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE permissions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(100) NOT NULL UNIQUE,
    display_name VARCHAR(255),
    description TEXT,
    resource VARCHAR(100) NOT NULL,
    action VARCHAR(50) NOT NULL,
    is_system BOOLEAN NOT NULL DEFAULT false,
    is_active BOOLEAN NOT NULL DEFAULT true,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE role_permissions (
    role_id UUID NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    permission_id UUID NOT NULL REFERENCES permissions(id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_id)
);

CREATE TABLE user_roles (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_id UUID NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    project_id UUID REFERENCES projects(id) ON DELETE CASCADE,
    assigned_by UUID REFERENCES users(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (user_id, role_id, project_id)
);

CREATE TABLE project_members (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_id UUID NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    assigned_by UUID REFERENCES users(id),
    is_active BOOLEAN NOT NULL DEFAULT true,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (project_id, user_id, role_id)
);

-- =========================================================
-- MOBILE / APP USERS
-- =========================================================

CREATE TABLE app_users (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
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
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    last_sync_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by UUID REFERENCES users(id),
    updated_by UUID REFERENCES users(id)
);

-- =========================================================
-- REFRESH TOKENS
-- Matches org.odk.platform.domain.RefreshToken.
-- A token can belong to either a web User or a mobile AppUser.
-- =========================================================

CREATE TABLE refresh_tokens (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    token VARCHAR(500) NOT NULL UNIQUE,
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    app_user_id UUID REFERENCES app_users(id) ON DELETE CASCADE,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked BOOLEAN NOT NULL DEFAULT false,
    revoked_at TIMESTAMP WITH TIME ZONE,
    replaced_by_token VARCHAR(500),
    ip_address VARCHAR(45),
    user_agent VARCHAR(500),
    metadata JSONB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);


-- =========================================================
-- EMAIL VERIFICATION TOKENS
-- Matches org.odk.platform.domain.EmailVerificationToken.
-- =========================================================

CREATE TABLE email_verification_tokens (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
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

-- =========================================================
-- PASSWORD RESET TOKENS
-- Matches org.odk.platform.domain.PasswordResetToken.
-- =========================================================

CREATE TABLE password_reset_tokens (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
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

-- =========================================================
-- XFORMS
-- This is the canonical form table used by the current XForm entity.
-- Do not recreate the obsolete "forms" table.
-- current_version_id is added in V7 after form_versions exists.
-- =========================================================

CREATE TABLE xforms (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
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
    settings JSONB NOT NULL DEFAULT '{}'::jsonb,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    published_at TIMESTAMP WITH TIME ZONE,
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    created_by_id UUID NOT NULL REFERENCES users(id),
    updated_by_id UUID REFERENCES users(id),
    published_by_id UUID REFERENCES users(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE app_user_projects (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    app_user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    form_id UUID REFERENCES xforms(id) ON DELETE SET NULL,
    assigned_by UUID REFERENCES users(id),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    assigned_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (app_user_id, project_id)
);

CREATE TABLE form_versions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    form_id UUID NOT NULL REFERENCES xforms(id) ON DELETE CASCADE,
    version VARCHAR(50) NOT NULL,
    xml_content TEXT NOT NULL,
    xls_content TEXT,
    xmlns VARCHAR(500),
    is_published BOOLEAN NOT NULL DEFAULT false,
    is_latest BOOLEAN NOT NULL DEFAULT false,
    changelog TEXT,
    settings JSONB NOT NULL DEFAULT '{}'::jsonb,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    published_at TIMESTAMP WITH TIME ZONE,
    published_by UUID REFERENCES users(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by UUID REFERENCES users(id),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by_id UUID REFERENCES users(id),
    published_by_id UUID REFERENCES users(id),
    UNIQUE (form_id, version)
);

-- =========================================================
-- SUBMISSIONS
-- =========================================================

CREATE TABLE submissions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    form_id UUID NOT NULL REFERENCES xforms(id) ON DELETE CASCADE,
    form_version_id UUID REFERENCES form_versions(id) ON DELETE SET NULL,
    instance_id VARCHAR(255) NOT NULL,
    xml_content TEXT NOT NULL,
    json_content JSONB,
    status VARCHAR(20) NOT NULL DEFAULT 'SUBMITTED',
    submitted_at TIMESTAMP WITH TIME ZONE NOT NULL,
    reviewed_at TIMESTAMP WITH TIME ZONE,
    reviewed_by_id UUID REFERENCES users(id),
    review_notes TEXT,
    metadata JSONB DEFAULT '{}'::jsonb,
    attachments JSONB DEFAULT '[]'::jsonb,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    submitted_by_id UUID REFERENCES app_users(id),
    CONSTRAINT uk_submissions_form_instance UNIQUE (form_id, instance_id)
);

CREATE TABLE submission_attachments (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    submission_id UUID NOT NULL REFERENCES submissions(id) ON DELETE CASCADE,
    file_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(255),
    file_size BIGINT,
    storage_path VARCHAR(500) NOT NULL,
    storage_driver VARCHAR(50) NOT NULL DEFAULT 'local',
    content_hash VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- =========================================================
-- AUDIT
-- =========================================================

CREATE TABLE audit_logs (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    action VARCHAR(100) NOT NULL,
    resource_type VARCHAR(100),
    resource_id VARCHAR(100),
    resource_name VARCHAR(255),
    old_values JSONB,
    new_values JSONB,
    ip_address VARCHAR(45),
    user_agent VARCHAR(500),
    request_id VARCHAR(100),
    session_id VARCHAR(100),
    status VARCHAR(20) NOT NULL DEFAULT 'SUCCESS',
    error_message TEXT,
    metadata JSONB,
    user_id UUID REFERENCES users(id),
    app_user_id UUID REFERENCES app_users(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- =========================================================
-- INDEXES
-- =========================================================

CREATE INDEX idx_users_username ON users(username);
CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_is_active ON users(is_active);

CREATE INDEX idx_projects_is_active ON projects(is_active);
CREATE INDEX idx_projects_owner_id ON projects(owner_id);

CREATE INDEX idx_roles_project_id ON roles(project_id);

CREATE INDEX idx_user_roles_user_id ON user_roles(user_id);
CREATE INDEX idx_user_roles_role_id ON user_roles(role_id);
CREATE INDEX idx_user_roles_project_id ON user_roles(project_id);

CREATE INDEX idx_project_members_project_id ON project_members(project_id);
CREATE INDEX idx_project_members_user_id ON project_members(user_id);

CREATE INDEX idx_app_users_username ON app_users(username);

CREATE UNIQUE INDEX idx_refresh_tokens_token ON refresh_tokens(token);
CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens(user_id);
CREATE INDEX idx_refresh_tokens_app_user_id ON refresh_tokens(app_user_id);
CREATE INDEX idx_refresh_tokens_expires_at ON refresh_tokens(expires_at);
CREATE INDEX idx_refresh_tokens_revoked ON refresh_tokens(revoked);

CREATE INDEX idx_app_user_projects_app_user_id ON app_user_projects(app_user_id);
CREATE INDEX idx_app_user_projects_project_id ON app_user_projects(project_id);
CREATE INDEX idx_app_user_projects_form_id ON app_user_projects(form_id);

CREATE INDEX idx_xforms_project_id ON xforms(project_id);
CREATE INDEX idx_xforms_xmlns ON xforms(xmlns);
CREATE INDEX idx_xforms_is_published ON xforms(is_published);
CREATE INDEX idx_xforms_is_active ON xforms(is_active);

CREATE INDEX idx_form_versions_form_id ON form_versions(form_id);
CREATE INDEX idx_form_versions_is_published ON form_versions(is_published);
CREATE INDEX idx_form_versions_is_latest ON form_versions(is_latest);

CREATE INDEX idx_submissions_form_id ON submissions(form_id);
CREATE INDEX idx_submissions_form_version_id ON submissions(form_version_id);
CREATE INDEX idx_submissions_instance_id ON submissions(instance_id);
CREATE INDEX idx_submissions_submitted_by_id ON submissions(submitted_by_id);
CREATE INDEX idx_submissions_status ON submissions(status);
CREATE INDEX idx_submissions_reviewed_by_id ON submissions(reviewed_by_id);
CREATE INDEX idx_submissions_submitted_at ON submissions(submitted_at);
CREATE INDEX idx_submissions_created_at ON submissions(created_at);

CREATE INDEX idx_submission_attachments_submission_id
    ON submission_attachments(submission_id);

CREATE INDEX idx_audit_logs_user_id ON audit_logs(user_id);
CREATE INDEX idx_audit_logs_app_user_id ON audit_logs(app_user_id);
CREATE INDEX idx_audit_logs_action ON audit_logs(action);
CREATE INDEX idx_audit_logs_resource_type ON audit_logs(resource_type);
CREATE INDEX idx_audit_logs_resource_id ON audit_logs(resource_id);
CREATE INDEX idx_audit_logs_created_at ON audit_logs(created_at);
CREATE INDEX idx_audit_logs_ip_address ON audit_logs(ip_address);

-- =========================================================
-- SYSTEM ROLES
-- =========================================================

INSERT INTO roles (
    id, name, display_name, description, is_system, is_active
) VALUES
    (uuid_generate_v4(), 'SYSTEM_ADMIN', 'System Administrator', 'System administrator with full access', true, true),
    (uuid_generate_v4(), 'PROJECT_ADMIN', 'Project Administrator', 'Project administrator', true, true),
    (uuid_generate_v4(), 'PROJECT_MANAGER', 'Project Manager', 'Project manager', true, true),
    (uuid_generate_v4(), 'DATA_ANALYST', 'Data Analyst', 'Data analyst with read access', true, true),
    (uuid_generate_v4(), 'DATA_COLLECTOR', 'Data Collector', 'Data collector / field worker', true, true),
    (uuid_generate_v4(), 'VIEWER', 'Viewer', 'Read-only viewer', true, true)
ON CONFLICT (name) DO NOTHING;

-- =========================================================
-- SYSTEM PERMISSIONS
-- =========================================================

INSERT INTO permissions (
    id, name, description, resource, action, is_system, is_active
) VALUES
    (uuid_generate_v4(), 'USER_CREATE', 'Create users', 'user', 'create', true, true),
    (uuid_generate_v4(), 'USER_READ', 'Read users', 'user', 'read', true, true),
    (uuid_generate_v4(), 'USER_UPDATE', 'Update users', 'user', 'update', true, true),
    (uuid_generate_v4(), 'USER_DELETE', 'Delete users', 'user', 'delete', true, true),

    (uuid_generate_v4(), 'PROJECT_CREATE', 'Create projects', 'project', 'create', true, true),
    (uuid_generate_v4(), 'PROJECT_READ', 'Read projects', 'project', 'read', true, true),
    (uuid_generate_v4(), 'PROJECT_UPDATE', 'Update projects', 'project', 'update', true, true),
    (uuid_generate_v4(), 'PROJECT_DELETE', 'Delete projects', 'project', 'delete', true, true),

    (uuid_generate_v4(), 'FORM_CREATE', 'Create forms', 'form', 'create', true, true),
    (uuid_generate_v4(), 'FORM_READ', 'Read forms', 'form', 'read', true, true),
    (uuid_generate_v4(), 'FORM_UPDATE', 'Update forms', 'form', 'update', true, true),
    (uuid_generate_v4(), 'FORM_DELETE', 'Delete forms', 'form', 'delete', true, true),
    (uuid_generate_v4(), 'FORM_PUBLISH', 'Publish forms', 'form', 'publish', true, true),

    (uuid_generate_v4(), 'SUBMISSION_READ', 'Read submissions', 'submission', 'read', true, true),
    (uuid_generate_v4(), 'SUBMISSION_UPDATE', 'Update submissions', 'submission', 'update', true, true),
    (uuid_generate_v4(), 'SUBMISSION_DELETE', 'Delete submissions', 'submission', 'delete', true, true),

    (uuid_generate_v4(), 'REPORT_CREATE', 'Create reports', 'report', 'create', true, true),
    (uuid_generate_v4(), 'REPORT_READ', 'Read reports', 'report', 'read', true, true),
    (uuid_generate_v4(), 'REPORT_EXPORT', 'Export reports', 'report', 'export', true, true),

    (uuid_generate_v4(), 'AUDIT_READ', 'Read audit logs', 'audit', 'read', true, true),

    (uuid_generate_v4(), 'APP_USER_CREATE', 'Create app users', 'app_user', 'create', true, true),
    (uuid_generate_v4(), 'APP_USER_READ', 'Read app users', 'app_user', 'read', true, true),
    (uuid_generate_v4(), 'APP_USER_UPDATE', 'Update app users', 'app_user', 'update', true, true),
    (uuid_generate_v4(), 'APP_USER_DELETE', 'Delete app users', 'app_user', 'delete', true, true),

    (uuid_generate_v4(), 'REFERENCE_DATA_CREATE', 'Create reference data', 'reference_data', 'create', true, true),
    (uuid_generate_v4(), 'REFERENCE_DATA_READ', 'Read reference data', 'reference_data', 'read', true, true),
    (uuid_generate_v4(), 'REFERENCE_DATA_UPDATE', 'Update reference data', 'reference_data', 'update', true, true),
    (uuid_generate_v4(), 'REFERENCE_DATA_DELETE', 'Delete reference data', 'reference_data', 'delete', true, true)
ON CONFLICT (name) DO NOTHING;

-- SYSTEM_ADMIN receives every permission.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.name = 'SYSTEM_ADMIN'
ON CONFLICT DO NOTHING;

-- PROJECT_ADMIN receives project/form/submission/report/app-user/reference-data permissions.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.name = 'PROJECT_ADMIN'
  AND p.name IN (
      'PROJECT_READ', 'PROJECT_UPDATE',
      'FORM_CREATE', 'FORM_READ', 'FORM_UPDATE', 'FORM_DELETE', 'FORM_PUBLISH',
      'SUBMISSION_READ', 'SUBMISSION_UPDATE',
      'REPORT_CREATE', 'REPORT_READ', 'REPORT_EXPORT',
      'APP_USER_CREATE', 'APP_USER_READ', 'APP_USER_UPDATE', 'APP_USER_DELETE',
      'REFERENCE_DATA_CREATE', 'REFERENCE_DATA_READ',
      'REFERENCE_DATA_UPDATE', 'REFERENCE_DATA_DELETE'
  )
ON CONFLICT DO NOTHING;

-- PROJECT_MANAGER receives the operational project/form permissions used by the API.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.name = 'PROJECT_MANAGER'
  AND p.name IN (
      'PROJECT_READ', 'PROJECT_UPDATE',
      'FORM_CREATE', 'FORM_READ', 'FORM_UPDATE', 'FORM_DELETE', 'FORM_PUBLISH',
      'SUBMISSION_READ', 'SUBMISSION_UPDATE',
      'REPORT_READ', 'REPORT_EXPORT',
      'APP_USER_READ',
      'REFERENCE_DATA_READ'
  )
ON CONFLICT DO NOTHING;

-- DATA_ANALYST receives read/report permissions.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.name = 'DATA_ANALYST'
  AND p.name IN (
      'PROJECT_READ', 'FORM_READ', 'SUBMISSION_READ',
      'REPORT_CREATE', 'REPORT_READ', 'REPORT_EXPORT',
      'REFERENCE_DATA_READ'
  )
ON CONFLICT DO NOTHING;

-- VIEWER receives read-only permissions.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.name = 'VIEWER'
  AND p.name IN (
      'PROJECT_READ', 'FORM_READ', 'SUBMISSION_READ',
      'REPORT_READ', 'APP_USER_READ', 'REFERENCE_DATA_READ'
  )
ON CONFLICT DO NOTHING;

-- DATA_COLLECTOR can read forms and submissions.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.name = 'DATA_COLLECTOR'
  AND p.name IN ('FORM_READ', 'SUBMISSION_READ')
ON CONFLICT DO NOTHING;

-- ============================================================================
-- CONSOLIDATED FROM: V2__seed_admin_user.sql
-- ============================================================================

-- V2__seed_admin_user.sql
-- Seed the default local-development administrator.
-- Password: admin123
-- BCrypt hash retained from the existing migration.

INSERT INTO users (
    id,
    username,
    email,
    password_hash,
    first_name,
    last_name,
    is_active,
    is_verified,
    created_at,
    updated_at
)
SELECT
    uuid_generate_v4(),
    'admin',
    'admin@odk-platform.local',
    '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVEFDa',
    'System',
    'Administrator',
    true,
    true,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1
    FROM users
    WHERE username = 'admin'
);

INSERT INTO user_roles (
    id,
    user_id,
    role_id,
    project_id,
    assigned_by,
    created_at,
    updated_at
)
SELECT
    uuid_generate_v4(),
    u.id,
    r.id,
    NULL,
    u.id,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
FROM users u
JOIN roles r ON r.name = 'SYSTEM_ADMIN'
WHERE u.username = 'admin'
  AND NOT EXISTS (
      SELECT 1
      FROM user_roles ur
      WHERE ur.user_id = u.id
        AND ur.role_id = r.id
        AND ur.project_id IS NULL
  );

-- ============================================================================
-- CONSOLIDATED FROM: V3__project_settings.sql
-- ============================================================================

-- V3__project_settings.sql
-- Per-project configuration.

CREATE TABLE project_settings (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    setting_key VARCHAR(255) NOT NULL,
    setting_value JSONB,
    description TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by UUID REFERENCES users(id),
    updated_by UUID REFERENCES users(id),
    UNIQUE (project_id, setting_key)
);

CREATE INDEX idx_project_settings_project_id
    ON project_settings(project_id);

CREATE INDEX idx_project_settings_setting_key
    ON project_settings(setting_key);

-- These inserts only affect projects that already exist when the migration runs.
-- ProjectService remains responsible for defaults on projects created later.

INSERT INTO project_settings (
    project_id, setting_key, setting_value, description, created_by
)
SELECT
    p.id,
    'allow_anonymous_submissions',
    'false'::jsonb,
    'Allow anonymous form submissions without authentication',
    p.created_by_id
FROM projects p
ON CONFLICT (project_id, setting_key) DO NOTHING;

INSERT INTO project_settings (
    project_id, setting_key, setting_value, description, created_by
)
SELECT
    p.id,
    'require_review_before_approval',
    'false'::jsonb,
    'Require admin review before submissions are approved',
    p.created_by_id
FROM projects p
ON CONFLICT (project_id, setting_key) DO NOTHING;

INSERT INTO project_settings (
    project_id, setting_key, setting_value, description, created_by
)
SELECT
    p.id,
    'default_form_status',
    '"draft"'::jsonb,
    'Default status for new forms (draft, validated, published)',
    p.created_by_id
FROM projects p
ON CONFLICT (project_id, setting_key) DO NOTHING;

INSERT INTO project_settings (
    project_id, setting_key, setting_value, description, created_by
)
SELECT
    p.id,
    'max_submission_size_mb',
    '10'::jsonb,
    'Maximum submission size in megabytes',
    p.created_by_id
FROM projects p
ON CONFLICT (project_id, setting_key) DO NOTHING;

INSERT INTO project_settings (
    project_id, setting_key, setting_value, description, created_by
)
SELECT
    p.id,
    'timezone',
    '"UTC"'::jsonb,
    'Default timezone for the project',
    p.created_by_id
FROM projects p
ON CONFLICT (project_id, setting_key) DO NOTHING;

INSERT INTO project_settings (
    project_id, setting_key, setting_value, description, created_by
)
SELECT
    p.id,
    'supported_languages',
    '["en"]'::jsonb,
    'Supported languages for forms in this project',
    p.created_by_id
FROM projects p
ON CONFLICT (project_id, setting_key) DO NOTHING;

-- ============================================================================
-- CONSOLIDATED FROM: V4__form_attachments.sql
-- ============================================================================

-- V4__form_attachments.sql
-- Form media/attachment storage.
-- Column names match the current FormAttachment JPA entity.

CREATE TABLE form_attachments (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
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

-- ============================================================================
-- CONSOLIDATED FROM: V5__form_external_datasets.sql
-- ============================================================================

-- V5__form_external_datasets.sql
-- External datasets referenced by a form version (CSV/JSON/XML/GeoJSON/etc.).

CREATE TABLE form_external_datasets (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
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
    column_definitions JSONB,
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

-- ============================================================================
-- CONSOLIDATED FROM: V6__form_publish_history.sql
-- ============================================================================

-- V6__form_publish_history.sql
-- Form-version lifecycle history.

CREATE TABLE form_publish_history (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
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

-- ============================================================================
-- CONSOLIDATED FROM: V7__form_version_status_and_timestamps.sql
-- ============================================================================

-- V7__form_version_status_and_timestamps.sql
-- Complete the current form-version lifecycle model and link each XForm
-- to its selected/current FormVersion.
--
-- Because V1-V7 have never been deployed, V8 is no longer required.
-- The current_version_id change is folded into V7.

ALTER TABLE form_versions
    ADD COLUMN form_hash VARCHAR(64);

ALTER TABLE form_versions
    ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'DRAFT';

ALTER TABLE form_versions
    ADD COLUMN validated_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE form_versions
    ADD COLUMN deprecated_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE form_versions
    ADD COLUMN archived_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE form_versions
    ADD COLUMN validated_by_id UUID REFERENCES users(id);

ALTER TABLE form_versions
    ADD COLUMN deprecated_by_id UUID REFERENCES users(id);

ALTER TABLE form_versions
    ADD COLUMN archived_by_id UUID REFERENCES users(id);

ALTER TABLE form_versions
    ADD CONSTRAINT chk_form_versions_status
    CHECK (status IN ('DRAFT', 'VALIDATED', 'PUBLISHED', 'DEPRECATED', 'ARCHIVED'));

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

-- XForm.currentVersion -> form_versions.id
ALTER TABLE xforms
    ADD COLUMN current_version_id UUID;

ALTER TABLE xforms
    ADD CONSTRAINT fk_xforms_current_version
    FOREIGN KEY (current_version_id)
    REFERENCES form_versions(id)
    ON DELETE SET NULL;

CREATE INDEX idx_xforms_current_version_id
    ON xforms(current_version_id);

-- ============================================================================
-- CONSOLIDATED FROM: V8__submission_answers_and_events.sql
-- ============================================================================

-- V8__submission_answers_and_events.sql
-- Add submission_answers and submission_events tables for the submission processing pipeline

-- =========================================================
-- SUBMISSION ANSWERS
-- Flattened answers for reporting and querying
-- =========================================================

CREATE TABLE submission_answers (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    submission_id UUID NOT NULL REFERENCES submissions(id) ON DELETE CASCADE,
    path VARCHAR(500) NOT NULL,
    field_name VARCHAR(255) NOT NULL,
    field_type VARCHAR(100),
    value_text TEXT,
    value_number NUMERIC,
    value_date TIMESTAMP WITH TIME ZONE,
    value_boolean BOOLEAN,
    value_json JSONB,
    repeat_path VARCHAR(500),
    repeat_index INTEGER,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- =========================================================
-- SUBMISSION EVENTS
-- Submission lifecycle events for audit trail
-- =========================================================

CREATE TABLE submission_events (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    submission_id UUID NOT NULL REFERENCES submissions(id) ON DELETE CASCADE,
    event_type VARCHAR(50) NOT NULL,
    event_status VARCHAR(20) NOT NULL DEFAULT 'success',
    actor_type VARCHAR(50),
    actor_id UUID,
    details JSONB,
    error_message TEXT,
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

-- ============================================================================
-- CONSOLIDATED FROM: V9__submission_versions.sql
-- ============================================================================

-- V9__submission_versions.sql
-- Add submission_versions table for replacement history tracking

-- =========================================================
-- SUBMISSION VERSIONS
-- Track submission replacements/updates for audit trail
-- =========================================================

CREATE TABLE submission_versions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    submission_id UUID NOT NULL REFERENCES submissions(id) ON DELETE CASCADE,
    version_number INTEGER NOT NULL,
    xml_content TEXT NOT NULL,
    json_content JSONB,
    instance_id VARCHAR(255) NOT NULL,
    deprecated_id VARCHAR(255),
    status VARCHAR(20) NOT NULL DEFAULT 'SUBMITTED',
    metadata JSONB,
    submitted_by_id UUID REFERENCES app_users(id),
    submitted_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- =========================================================
-- INDEXES
-- =========================================================

CREATE INDEX idx_submission_versions_submission_id ON submission_versions(submission_id);
CREATE INDEX idx_submission_versions_instance_id ON submission_versions(instance_id);
CREATE INDEX idx_submission_versions_deprecated_id ON submission_versions(deprecated_id);
CREATE INDEX idx_submission_versions_version_number ON submission_versions(submission_id, version_number);
CREATE INDEX idx_submission_versions_submitted_at ON submission_versions(submitted_at);

-- =========================================================
-- CONSTRAINTS
-- =========================================================

-- Ensure version numbers are unique per submission
ALTER TABLE submission_versions ADD CONSTRAINT uk_submission_versions_submission_version 
    UNIQUE (submission_id, version_number);

-- ============================================================================
-- CONSOLIDATED FROM: V11__submission_attachment_metadata.sql
-- ============================================================================

-- V11__submission_attachment_metadata.sql
-- Add attachment-level metadata required by the submission processing pipeline.
--
-- Used to record facts such as whether an uploaded file is referenced by one
-- or more submission answers and, later, answer-path linkage information.

ALTER TABLE submission_attachments
    ADD COLUMN metadata JSONB NOT NULL DEFAULT '{}'::jsonb;

-- ============================================================================
-- CONSOLIDATED FROM: V12__web_form_sessions.sql
-- ============================================================================

-- V12__web_form_sessions.sql
-- Web form runtime session tables for mobile-first browser form filling

-- =========================================================
-- WEB FORM SESSIONS
-- =========================================================

CREATE TABLE web_form_sessions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    form_version_id UUID NOT NULL REFERENCES form_versions(id) ON DELETE CASCADE,
    user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    app_user_id UUID REFERENCES app_users(id) ON DELETE SET NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'draft',
    current_screen VARCHAR(255),
    current_question_path VARCHAR(500),
    session_data JSONB NOT NULL DEFAULT '{}'::jsonb,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_activity_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP WITH TIME ZONE,
    submitted_at TIMESTAMP WITH TIME ZONE,
    submission_id UUID REFERENCES submissions(id) ON DELETE SET NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by UUID REFERENCES users(id),
    updated_by UUID REFERENCES users(id)
);

-- =========================================================
-- WEB FORM ANSWERS
-- =========================================================

CREATE TABLE web_form_answers (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    session_id UUID NOT NULL REFERENCES web_form_sessions(id) ON DELETE CASCADE,
    question_path VARCHAR(500) NOT NULL,
    question_name VARCHAR(255),
    question_type VARCHAR(100),
    value_text TEXT,
    value_number NUMERIC,
    value_date TIMESTAMP WITH TIME ZONE,
    value_boolean BOOLEAN,
    value_json JSONB,
    repeat_path VARCHAR(500),
    repeat_index INTEGER,
    is_relevant BOOLEAN NOT NULL DEFAULT true,
    is_required BOOLEAN NOT NULL DEFAULT false,
    constraint_message TEXT,
    validation_status VARCHAR(50) NOT NULL DEFAULT 'pending',
    answered_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- =========================================================
-- WEB FORM ATTACHMENTS
-- =========================================================

CREATE TABLE web_form_attachments (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    session_id UUID NOT NULL REFERENCES web_form_sessions(id) ON DELETE CASCADE,
    answer_id UUID REFERENCES web_form_answers(id) ON DELETE SET NULL,
    question_path VARCHAR(500),
    file_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(255),
    file_size BIGINT,
    storage_path VARCHAR(500) NOT NULL,
    storage_driver VARCHAR(50) NOT NULL DEFAULT 'local',
    content_hash VARCHAR(64),
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- =========================================================
-- INDEXES
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
CREATE INDEX idx_web_form_answers_repeat_path ON web_form_answers(repeat_path);
CREATE INDEX idx_web_form_answers_validation_status ON web_form_answers(validation_status);

CREATE INDEX idx_web_form_attachments_session_id ON web_form_attachments(session_id);
CREATE INDEX idx_web_form_attachments_answer_id ON web_form_attachments(answer_id);

-- ============================================================================
-- CONSOLIDATED FROM: V15__add_web_form_answer_instance_path.sql
-- ============================================================================

-- Preserve the exact JavaRosa instance location for answers inside nested repeats.
ALTER TABLE web_form_answers
    ADD COLUMN IF NOT EXISTS instance_path VARCHAR(1000);

-- Existing rows were created before instance-specific paths were available.
-- question_path is a safe legacy fallback only for non-repeat answers. Older
-- repeat rows must remain NULL because their canonical question_path is shared
-- by every repeat instance; the exact JavaRosa path is filled the next time
-- that answer is saved.
UPDATE web_form_answers
SET instance_path = question_path
WHERE instance_path IS NULL
  AND (repeat_path IS NULL OR repeat_path = '');

CREATE INDEX IF NOT EXISTS idx_web_form_answers_instance_path
    ON web_form_answers(instance_path);

-- ============================================================================
-- CONSOLIDATED FROM: V16__entity_list_lookup_foundation.sql
-- ============================================================================

-- Generic project-scoped Entity Lists used by ODK/XForm dataset lookups.
-- Management/import/export UI is implemented separately; this migration provides
-- the neutral persistence model required by the JavaRosa web runtime.

CREATE TABLE entity_lists (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
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
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    entity_list_id UUID NOT NULL REFERENCES entity_lists(id) ON DELETE CASCADE,
    entity_id VARCHAR(255) NOT NULL,
    label TEXT NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (entity_list_id, entity_id)
);

CREATE INDEX idx_entity_items_entity_list_id ON entity_items(entity_list_id);
CREATE INDEX idx_entity_items_entity_id ON entity_items(entity_id);
CREATE INDEX idx_entity_items_is_active ON entity_items(is_active);

CREATE TABLE entity_item_properties (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    entity_item_id UUID NOT NULL REFERENCES entity_items(id) ON DELETE CASCADE,
    property_name VARCHAR(255) NOT NULL,
    property_value TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (entity_item_id, property_name)
);

CREATE INDEX idx_entity_item_properties_item_id ON entity_item_properties(entity_item_id);
CREATE INDEX idx_entity_item_properties_name ON entity_item_properties(property_name);

-- ============================================================================
-- CONSOLIDATED FROM: V17__generic_reference_data.sql
-- ============================================================================

-- Generic project-scoped reference data. The model deliberately avoids
-- location-specific concepts such as district/parish/village.

CREATE TABLE reference_lists (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    display_name VARCHAR(255),
    description TEXT,
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (project_id, name)
);
CREATE INDEX idx_reference_lists_project_id ON reference_lists(project_id);
CREATE INDEX idx_reference_lists_name ON reference_lists(name);
CREATE INDEX idx_reference_lists_is_active ON reference_lists(is_active);

CREATE TABLE reference_items (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    reference_list_id UUID NOT NULL REFERENCES reference_lists(id) ON DELETE CASCADE,
    item_key VARCHAR(255) NOT NULL,
    label TEXT NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (reference_list_id, item_key)
);
CREATE INDEX idx_reference_items_list_id ON reference_items(reference_list_id);
CREATE INDEX idx_reference_items_item_key ON reference_items(item_key);
CREATE INDEX idx_reference_items_is_active ON reference_items(is_active);

CREATE TABLE reference_item_properties (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    reference_item_id UUID NOT NULL REFERENCES reference_items(id) ON DELETE CASCADE,
    property_name VARCHAR(255) NOT NULL,
    property_value TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (reference_item_id, property_name)
);
CREATE INDEX idx_reference_item_properties_item_id ON reference_item_properties(reference_item_id);
CREATE INDEX idx_reference_item_properties_name ON reference_item_properties(property_name);

CREATE TABLE reference_item_relationships (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    parent_item_id UUID NOT NULL REFERENCES reference_items(id) ON DELETE CASCADE,
    child_item_id UUID NOT NULL REFERENCES reference_items(id) ON DELETE CASCADE,
    relationship_type VARCHAR(100) NOT NULL DEFAULT 'parent',
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_reference_relationship_not_self CHECK (parent_item_id <> child_item_id),
    UNIQUE (parent_item_id, child_item_id, relationship_type)
);
CREATE INDEX idx_reference_relationships_parent ON reference_item_relationships(parent_item_id);
CREATE INDEX idx_reference_relationships_child ON reference_item_relationships(child_item_id);
CREATE INDEX idx_reference_relationships_type ON reference_item_relationships(relationship_type);

-- ============================================================================
-- CONSOLIDATED FROM: V18__generic_reporting.sql
-- ============================================================================

-- V18__generic_reporting.sql
-- Generic, form-driven reporting configuration and run/export history.
-- No domain-specific dimensions are hard-coded: answer fields are identified by
-- their canonical submission answer paths and system fields use the $submission.* namespace.

CREATE TABLE report_definitions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
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
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
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
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    report_definition_id UUID NOT NULL REFERENCES report_definitions(id) ON DELETE CASCADE,
    field_path VARCHAR(500) NOT NULL,
    field_kind VARCHAR(30) NOT NULL DEFAULT 'ANSWER',
    operator VARCHAR(30) NOT NULL,
    value_text TEXT,
    value_to TEXT,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_report_filters_kind CHECK (field_kind IN ('ANSWER', 'SYSTEM')),
    CONSTRAINT chk_report_filters_operator CHECK (operator IN (
        'EQ', 'NE', 'CONTAINS', 'GT', 'GTE', 'LT', 'LTE', 'IN', 'BETWEEN', 'IS_EMPTY', 'NOT_EMPTY'
    ))
);

CREATE TABLE report_runs (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    report_definition_id UUID NOT NULL REFERENCES report_definitions(id) ON DELETE CASCADE,
    run_by_id UUID REFERENCES users(id) ON DELETE SET NULL,
    parameters TEXT,
    row_count INTEGER NOT NULL DEFAULT 0,
    source_submission_count INTEGER NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'RUNNING',
    duration_ms BIGINT,
    error_message TEXT,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT chk_report_runs_status CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED'))
);

CREATE TABLE report_exports (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    report_run_id UUID NOT NULL REFERENCES report_runs(id) ON DELETE CASCADE,
    format VARCHAR(20) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(255) NOT NULL,
    file_size BIGINT NOT NULL,
    created_by_id UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_report_exports_format CHECK (format IN ('CSV', 'XLSX'))
);

CREATE TABLE dashboard_widgets (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    report_definition_id UUID REFERENCES report_definitions(id) ON DELETE CASCADE,
    title VARCHAR(255) NOT NULL,
    widget_type VARCHAR(30) NOT NULL DEFAULT 'TABLE',
    config TEXT,
    sort_order INTEGER NOT NULL DEFAULT 0,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_dashboard_widgets_type CHECK (widget_type IN ('TABLE', 'NUMBER', 'BAR', 'LINE', 'PIE'))
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

-- ============================================================================
-- CONSOLIDATED FROM: V19__audit_log_details.sql
-- ============================================================================

-- V19__audit_log_details.sql
-- Structured per-field/details for an audit event. The parent audit_logs row remains
-- the searchable event envelope; this table stores optional granular changes.

CREATE TABLE audit_log_details (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    audit_log_id UUID NOT NULL REFERENCES audit_logs(id) ON DELETE CASCADE,
    detail_key VARCHAR(255) NOT NULL,
    old_value TEXT,
    new_value TEXT,
    value_type VARCHAR(50),
    metadata TEXT NOT NULL DEFAULT '{}',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_audit_log_details_audit_log_id ON audit_log_details(audit_log_id);
CREATE INDEX idx_audit_log_details_detail_key ON audit_log_details(detail_key);
CREATE INDEX idx_audit_log_details_created_at ON audit_log_details(created_at);

-- ============================================================================
-- CONSOLIDATED FROM: V20__allow_multiple_form_assignments_per_app_user.sql
-- ============================================================================

-- V20__allow_multiple_form_assignments_per_app_user.sql
-- V1 allowed only one app_user_projects row per app user/project. The current
-- assignment service intentionally supports several different forms in the
-- same project, so uniqueness belongs on app-user + project + form.

ALTER TABLE app_user_projects
    DROP CONSTRAINT IF EXISTS app_user_projects_app_user_id_project_id_key;

-- Defensive cleanup for databases once created from JPA metadata rather than
-- the canonical V1 Flyway script.
DROP INDEX IF EXISTS idx_app_user_projects_app_user_project;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'uk_app_user_projects_user_project_form'
          AND conrelid = 'app_user_projects'::regclass
    ) THEN
        ALTER TABLE app_user_projects
            ADD CONSTRAINT uk_app_user_projects_user_project_form
            UNIQUE (app_user_id, project_id, form_id);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_app_user_projects_app_user_id
    ON app_user_projects(app_user_id);
CREATE INDEX IF NOT EXISTS idx_app_user_projects_project_id
    ON app_user_projects(project_id);
CREATE INDEX IF NOT EXISTS idx_app_user_projects_form_id
    ON app_user_projects(form_id);

-- Preserve the old one-row project-level assignment behavior for the optional
-- NULL-form case while allowing several distinct form-specific rows.
CREATE UNIQUE INDEX IF NOT EXISTS uk_app_user_projects_user_project_no_form
    ON app_user_projects(app_user_id, project_id)
    WHERE form_id IS NULL;

-- ============================================================================
-- CONSOLIDATED FROM: V21__link_submission_attachments_to_answers.sql
-- ============================================================================

-- V21__link_submission_attachments_to_answers.sql
-- Persist the concrete normalized SubmissionAnswer referenced by a respondent
-- attachment. The link is nullable for older rows and unreferenced extra files.

ALTER TABLE submission_attachments
    ADD COLUMN answer_id UUID;

-- Backfill historical rows only when the filename identifies exactly one
-- normalized answer inside the same submission. Ambiguous matches remain null.
UPDATE submission_attachments attachment
SET answer_id = (
    SELECT ans.id
    FROM submission_answers ans
    WHERE ans.submission_id = attachment.submission_id
      AND ans.value_text = attachment.file_name
    LIMIT 1
)
WHERE attachment.answer_id IS NULL
  AND (
      SELECT COUNT(*)
      FROM submission_answers ans
      WHERE ans.submission_id = attachment.submission_id
        AND ans.value_text = attachment.file_name
  ) = 1;

ALTER TABLE submission_attachments
    ADD CONSTRAINT fk_submission_attachments_answer
    FOREIGN KEY (answer_id)
    REFERENCES submission_answers(id)
    ON DELETE SET NULL;

CREATE INDEX idx_submission_attachments_answer_id
    ON submission_attachments(answer_id);

-- ============================================================================
-- CONSOLIDATED FROM: V22__web_form_assignments.sql
-- ============================================================================

-- V22__web_form_assignments.sql
--
-- Project-scoped browser/web-form access assignments.
-- Mobile ODK/Kobo assignments remain in app_user_projects.
--
-- target_type + target_id is intentionally polymorphic so PostgreSQL and H2
-- share the same portable uniqueness contract. The service validates that
-- USER targets are active project members and ROLE targets are assignable
-- project roles.

CREATE TABLE web_form_assignments (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    form_id UUID NOT NULL REFERENCES xforms(id) ON DELETE CASCADE,
    target_type VARCHAR(20) NOT NULL,
    target_id UUID NOT NULL,
    assigned_by UUID REFERENCES users(id) ON DELETE SET NULL,
    is_active BOOLEAN NOT NULL DEFAULT true,
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
