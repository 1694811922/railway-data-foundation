CREATE TABLE workspace_account (
    user_id VARCHAR(40) NOT NULL COMMENT '管理工作台用户随机编号；与外部调用凭据/Kafka身份独立',
    username VARCHAR(64) NOT NULL COMMENT '不可变登录账号；服务端记录的真实历史操作人',
    display_name VARCHAR(120) NOT NULL COMMENT '中文用户显示名称',
    password_hash VARCHAR(200) NOT NULL COMMENT '带算法标记的BCrypt单向哈希；不保存明文密码',
    role VARCHAR(16) NOT NULL COMMENT '唯一工作台角色：ADMIN、PROVIDER、DEMAND',
    subject_id VARCHAR(40) NULL COMMENT '普通用户唯一关联已有sharing_subject；管理员为空',
    enabled BOOLEAN NOT NULL DEFAULT TRUE COMMENT '账号启用状态；停用后已有会话下次请求失效',
    version INT NOT NULL DEFAULT 1 COMMENT '账号/密码修改版本；已有会话版本不同则失效',
    created_at DATETIME(3) NOT NULL COMMENT '账号登记时间UTC',
    updated_at DATETIME(3) NOT NULL COMMENT '账号最近修改时间UTC',
    PRIMARY KEY(user_id), UNIQUE KEY uk_workspace_username(username), KEY idx_workspace_subject(subject_id),
    CONSTRAINT fk_workspace_subject FOREIGN KEY(subject_id) REFERENCES sharing_subject(subject_id),
    CONSTRAINT chk_workspace_role CHECK(role IN ('ADMIN','PROVIDER','DEMAND')),
    CONSTRAINT chk_workspace_binding CHECK((role='ADMIN' AND subject_id IS NULL) OR (role<>'ADMIN' AND subject_id IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='统一前端管理登录账号；不替代外部访问令牌或Kafka SCRAM';

ALTER TABLE source_system ADD provider_subject_id VARCHAR(40) NULL COMMENT '提供方主体归属；扫描/资源/能力继承；存量NULL仅管理员管理',
    ADD KEY idx_source_provider(provider_subject_id),
    ADD CONSTRAINT fk_source_provider FOREIGN KEY(provider_subject_id) REFERENCES sharing_subject(subject_id);

ALTER TABLE sharing_relationship_event
    MODIFY operator_name VARCHAR(120) NOT NULL COMMENT '新记录为真实登录账号或系统协调器；旧原型标签原样保留',
    ADD operator_user_id VARCHAR(40) NULL COMMENT '新历史的认证用户编号；旧记录和系统任务为空，不伪造';
ALTER TABLE caller_credential_event
    MODIFY operator_name VARCHAR(120) NOT NULL COMMENT '新记录为真实登录账号；历史原型标签原样保留',
    ADD operator_user_id VARCHAR(40) NULL COMMENT '新记录的认证工作台用户编号；旧历史为空';
ALTER TABLE kafka_subscription_event
    MODIFY operator_name VARCHAR(120) NOT NULL COMMENT '新记录为真实登录账号或系统协调器；历史标签原样保留',
    ADD operator_user_id VARCHAR(40) NULL COMMENT '认证工作台用户编号；旧历史和后台协调为空';
ALTER TABLE resource_revision
    ADD operator_user_id VARCHAR(40) NULL COMMENT '新资源快照操作人认证编号；旧历史不回填',
    ADD operator_name VARCHAR(120) NULL COMMENT '新快照真实登录账号；旧快照为空，不伪造旧操作人';
