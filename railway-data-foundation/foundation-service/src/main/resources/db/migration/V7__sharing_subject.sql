CREATE TABLE sharing_subject (
    subject_id VARCHAR(36) NOT NULL COMMENT '共享主体编号，SUB_加32位随机标识',
    subject_code VARCHAR(64) NOT NULL COMMENT '唯一业务编码，登记后不可修改；不是认证凭据',
    subject_name VARCHAR(120) NOT NULL COMMENT '提供方或需求方的显示名称',
    subject_type VARCHAR(16) NOT NULL COMMENT '主体角色：PROVIDER提供方、DEMAND需求方；登记后不可修改',
    owner_department VARCHAR(120) NOT NULL COMMENT '主体所属或责任单位',
    description VARCHAR(1000) NULL COMMENT '主体业务说明，不保存密码或业务明细',
    status VARCHAR(16) NOT NULL DEFAULT 'ENABLED' COMMENT '登记状态：ENABLED启用、DISABLED停用；不代表数据访问授权',
    version INT NOT NULL DEFAULT 1 COMMENT '乐观锁版本号，变更时递增，防止旧表单覆盖新信息',
    created_at DATETIME(3) NOT NULL COMMENT '登记时间，UTC',
    updated_at DATETIME(3) NOT NULL COMMENT '最近更新时间，UTC',
    PRIMARY KEY (subject_id),
    UNIQUE KEY uk_sharing_subject_code (subject_code),
    CONSTRAINT ck_sharing_subject_type CHECK (subject_type IN ('PROVIDER', 'DEMAND')),
    CONSTRAINT ck_sharing_subject_status CHECK (status IN ('ENABLED', 'DISABLED')),
    CONSTRAINT ck_sharing_subject_version CHECK (version >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='共享业务主体登记；不保存运行时身份凭据，不自动授予共享权限';
