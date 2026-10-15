CREATE TABLE caller_credential (
    credential_id VARCHAR(40) NOT NULL COMMENT '调用凭据编号，AUTH_加32位随机标识，不是令牌',
    subject_id VARCHAR(36) NOT NULL COMMENT '所属需求方主体编号，身份从凭据校验推导',
    credential_name VARCHAR(120) NOT NULL COMMENT '管理员填写的调用凭据名称',
    token_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '256位随机令牌的SHA-256摘要，禁止保存或回显明文',
    status VARCHAR(16) NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED可用、REVOKED已撤销，撤销不可恢复',
    expires_at DATETIME(3) NOT NULL COMMENT '凭据到期时间，不包含，UTC',
    version INT NOT NULL DEFAULT 1 COMMENT '乐观锁版本，撤销时递增',
    created_at DATETIME(3) NOT NULL COMMENT '签发时间，UTC',
    revoked_at DATETIME(3) NULL COMMENT '撤销时间，UTC',
    PRIMARY KEY (credential_id),
    UNIQUE KEY uk_caller_token_digest (token_digest),
    UNIQUE KEY uk_caller_credential_name (subject_id, credential_name),
    CONSTRAINT fk_caller_subject FOREIGN KEY (subject_id) REFERENCES sharing_subject(subject_id),
    CONSTRAINT ck_caller_status CHECK (status IN ('ENABLED','REVOKED')),
    CONSTRAINT ck_caller_version CHECK (version >= 1),
    CONSTRAINT ck_caller_revoke CHECK ((status='ENABLED' AND revoked_at IS NULL) OR (status='REVOKED' AND revoked_at IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='需求方可信调用凭据；只保存高熵令牌摘要，签发后无法回显';

CREATE TABLE caller_credential_event (
    event_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '凭据操作记录编号',
    credential_id VARCHAR(40) NOT NULL COMMENT '调用凭据编号',
    action VARCHAR(16) NOT NULL COMMENT 'ISSUE签发、REVOKE撤销',
    operator_name VARCHAR(40) NOT NULL COMMENT '服务端原型管理员标签，尚未接入管理员登录认证',
    reason VARCHAR(1000) NOT NULL COMMENT '必填操作原因',
    version INT NOT NULL COMMENT '操作后的凭据版本',
    occurred_at DATETIME(3) NOT NULL COMMENT '操作时间，UTC',
    PRIMARY KEY (event_id),
    UNIQUE KEY uk_caller_event_version (credential_id, version),
    CONSTRAINT fk_caller_event FOREIGN KEY (credential_id) REFERENCES caller_credential(credential_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='调用凭据签发与撤销历史，与变更同一事务，不保存令牌';
