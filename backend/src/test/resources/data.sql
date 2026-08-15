-- Test data for H2 test profile
-- Insert default admin user (password: admin123)
-- BCrypt hash for "admin123" with cost 12 (matches SecurityBeansConfig)
-- Note: Roles and permissions are already created by V1__initial_schema_test.sql migration
INSERT INTO users (id, username, email, password_hash, first_name, last_name, phone_number, avatar_url, is_active, is_verified, last_login_at, password_changed_at, failed_login_attempts, locked_until, preferred_language, timezone, metadata, is_superuser, created_at, updated_at, created_by, updated_by)
VALUES (
    RANDOM_UUID(),
    'admin',
    'admin@odk-platform.local',
    '$2a$12$xYWvZmiMdxV7ryx5OFvJzeg./OH.vas5ziq4UPnauSK9x0AIS/ixO',
    'System',
    'Administrator',
    NULL,
    NULL,
    true,
    true,
    NULL,
    NULL,
    0,
    NULL,
    'en',
    'UTC',
    '{}',
    true,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    NULL,
    NULL
);

-- Create a system project for SYSTEM_ADMIN role assignment (project_id cannot be NULL due to PK constraint)
INSERT INTO projects (id, name, description, is_active, created_at, updated_at, owner_id, created_by_id)
VALUES (
    '00000000-0000-0000-0000-000000000000',
    'System Project',
    'System project for system-wide roles',
    true,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    (SELECT id FROM users WHERE username = 'admin'),
    (SELECT id FROM users WHERE username = 'admin')
);

-- Assign SYSTEM_ADMIN role to admin user (using system project)
INSERT INTO user_roles (id, user_id, role_id, project_id, assigned_by, created_at, updated_at)
SELECT RANDOM_UUID(), u.id, r.id, '00000000-0000-0000-0000-000000000000', u.id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM users u, roles r
WHERE u.username = 'admin' AND r.name = 'SYSTEM_ADMIN';

-- Insert test project (id = 1 for test compatibility)
INSERT INTO projects (id, name, description, is_active, created_at, updated_at, owner_id, created_by_id)
VALUES (
    '00000000-0000-0000-0000-000000000001',
    'Test Project',
    'Test project for integration tests',
    true,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    (SELECT id FROM users WHERE username = 'admin'),
    (SELECT id FROM users WHERE username = 'admin')
);

-- Insert app user for OpenRosa Basic Auth tests (username: appuser, password: password123)
-- BCrypt hash for "password123" with cost 12
INSERT INTO app_users (id, username, email, password_hash, full_name, phone_number, avatar_url, is_active, is_verified, last_login_at, password_changed_at, failed_login_attempts, locked_until, preferred_language, timezone, metadata, last_sync_at, created_at, updated_at, created_by, updated_by)
VALUES (
    '11111111-1111-1111-1111-111111111111',
    'appuser',
    'appuser@odk-platform.local',
    '$2a$12$/EM5zT3Ys8Fa7JIYZfAaL.6FS/YUhqJe5X2YzZb2vmuxEzhvpzy1S',
    'Test App User',
    NULL,
    NULL,
    true,
    true,
    NULL,
    NULL,
    0,
    NULL,
    'en',
    'UTC',
    '{}',
    NULL,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    NULL,
    NULL
);

-- Assign app user to test project
INSERT INTO app_user_projects (id, app_user_id, project_id, form_id, assigned_by, status, metadata, assigned_at, created_at, updated_at)
VALUES (
    '22222222-2222-2222-2222-222222222222',
    '11111111-1111-1111-1111-111111111111',
    '00000000-0000-0000-0000-000000000001',
    NULL,
    (SELECT id FROM users WHERE username = 'admin'),
    'ACTIVE',
    '{}',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
);

-- Insert sample form for testing (using xforms table)
INSERT INTO xforms (id, title, description, xmlns, version, xml_content, xls_content, is_published, is_active, is_encrypted, encryption_key, settings, metadata, published_at, project_id, created_by_id, updated_by_id, published_by_id, created_at, updated_at)
VALUES (
    '33333333-3333-3333-3333-333333333333',
    'Sample Form',
    'A sample form for testing',
    'sample_form',
    '1.0',
    '<?xml version="1.0" encoding="UTF-8"?><h:html xmlns:h="http://www.w3.org/1999/xhtml" xmlns:orx="http://openrosa.org/xforms"><h:head><h:title>Sample Form</h:title><model><instance><data id="sample_form"><meta><instanceID/></meta><name/></data></instance></model></h:head><h:body><input ref="/data/name"><label>Name</label></input></h:body></h:html>',
    NULL,
    true,
    true,
    false,
    NULL,
    '{}',
    '{}',
    CURRENT_TIMESTAMP,
    '00000000-0000-0000-0000-000000000001',
    (SELECT id FROM users WHERE username = 'admin'),
    (SELECT id FROM users WHERE username = 'admin'),
    (SELECT id FROM users WHERE username = 'admin'),
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
);

-- Insert form version for the sample form (required for OpenRosa formList endpoint)
INSERT INTO form_versions (id, version, xml_content, xls_content, xmlns, is_published, is_latest, changelog, settings, metadata, published_at, form_id, created_by_id, published_by_id, created_at, updated_at)
VALUES (
    '44444444-4444-4444-4444-444444444444',
    '1.0',
    '<?xml version="1.0" encoding="UTF-8"?><h:html xmlns:h="http://www.w3.org/1999/xhtml" xmlns:orx="http://openrosa.org/xforms"><h:head><h:title>Sample Form</h:title><model><instance><data id="sample_form"><meta><instanceID/></meta><name/></data></instance></model></h:head><h:body><input ref="/data/name"><label>Name</label></input></h:body></h:html>',
    NULL,
    'sample_form',
    true,
    true,
    'Initial version',
    '{}',
    '{}',
    CURRENT_TIMESTAMP,
    '33333333-3333-3333-3333-333333333333',
    (SELECT id FROM users WHERE username = 'admin'),
    (SELECT id FROM users WHERE username = 'admin'),
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
);
