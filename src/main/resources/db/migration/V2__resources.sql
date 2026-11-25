CREATE TABLE sandbox_resource (
    resource_key VARCHAR(255) NOT NULL PRIMARY KEY,
    account VARCHAR(64) NOT NULL,
    kind VARCHAR(32) NOT NULL,
    resource_scope VARCHAR(128) NOT NULL,
    external_id VARCHAR(128) NOT NULL,
    payload TEXT NOT NULL,
    content LONGBLOB
);
CREATE INDEX sandbox_resource_scope ON sandbox_resource (account, kind, resource_scope, external_id);
CREATE INDEX sandbox_resource_ref ON sandbox_resource (account, kind, external_id);
