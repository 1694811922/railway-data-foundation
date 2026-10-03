CREATE TABLE source_credential (
    credential_ref VARCHAR(40) NOT NULL,
    username VARCHAR(128) NOT NULL,
    encrypted_value TEXT NOT NULL COMMENT 'AES-256-GCM v1；含随机IV，不保存明文',
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (credential_ref)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据源加密凭据；主密钥位于数据库之外';

ALTER TABLE source_system
    MODIFY connection_env_prefix VARCHAR(80) NULL COMMENT '兼容旧环境变量登记，新登记为空',
    ADD host VARCHAR(253) NULL,
    ADD port INT NULL,
    ADD database_name VARCHAR(64) NULL,
    ADD username VARCHAR(128) NULL,
    ADD ssl_mode VARCHAR(20) NULL,
    ADD credential_ref VARCHAR(40) NULL,
    ADD status VARCHAR(20) NOT NULL DEFAULT 'ENABLED',
    ADD CONSTRAINT chk_source_status CHECK (status IN ('ENABLED','DISABLED')),
    ADD CONSTRAINT fk_source_credential FOREIGN KEY (credential_ref) REFERENCES source_credential(credential_ref),
    ADD CONSTRAINT chk_source_connection CHECK (
        (connection_env_prefix IS NOT NULL AND host IS NULL AND port IS NULL AND database_name IS NULL
            AND username IS NULL AND ssl_mode IS NULL AND credential_ref IS NULL)
        OR (connection_env_prefix IS NULL AND host IS NOT NULL AND port IS NOT NULL AND port BETWEEN 1 AND 65535
            AND database_name IS NOT NULL AND username IS NOT NULL AND ssl_mode IS NOT NULL AND credential_ref IS NOT NULL)
    );
