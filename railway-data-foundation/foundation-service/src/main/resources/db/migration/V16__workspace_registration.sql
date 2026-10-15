CREATE TABLE workspace_registration_guard (
    guard_id INT NOT NULL COMMENT '固定为1；串行化账号登记和首次管理员初始化',
    PRIMARY KEY (guard_id)
) ENGINE=InnoDB COMMENT='账号注册并发保护；不存业务数据';
INSERT INTO workspace_registration_guard VALUES (1);

CREATE TABLE workspace_registration (
    registration_id VARCHAR(40) NOT NULL COMMENT '注册申请随机编号',
    username VARCHAR(64) NOT NULL COMMENT '申请登录账号；不是外部调用身份',
    display_name VARCHAR(120) NOT NULL COMMENT '申请人显示名称',
    requested_role VARCHAR(16) NOT NULL COMMENT '申请角色；仅PROVIDER或DEMAND',
    organization_name VARCHAR(120) NOT NULL COMMENT '申请人填写的单位名称；不直接授予主体归属',
    password_hash VARCHAR(200) NULL COMMENT '待审核BCrypt单向哈希；审核结束后清空',
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '审核状态：PENDING待审核、APPROVED通过、REJECTED拒绝',
    subject_id VARCHAR(40) NULL COMMENT '审核通过时管理员核定的已有共享主体',
    reviewed_by VARCHAR(40) NULL COMMENT '审核管理员真实用户编号',
    review_reason VARCHAR(500) NULL COMMENT '审核意见；拒绝时必填',
    version INT NOT NULL DEFAULT 1 COMMENT '审核并发版本',
    created_at DATETIME(3) NOT NULL COMMENT '注册提交时间UTC',
    reviewed_at DATETIME(3) NULL COMMENT '审核完成时间UTC',
    PRIMARY KEY (registration_id), UNIQUE KEY uk_registration_username (username),
    KEY idx_registration_status (status, created_at),
    CONSTRAINT fk_registration_subject FOREIGN KEY (subject_id) REFERENCES sharing_subject(subject_id),
    CONSTRAINT fk_registration_reviewer FOREIGN KEY (reviewed_by) REFERENCES workspace_account(user_id),
    CONSTRAINT chk_registration_role CHECK (requested_role IN ('PROVIDER','DEMAND')),
    CONSTRAINT chk_registration_status CHECK (status IN ('PENDING','APPROVED','REJECTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='普通用户注册申请；审核通过前没有登录账号和主体权限';
