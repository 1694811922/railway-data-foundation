CREATE TABLE source_system (
    source_id VARCHAR(40) NOT NULL COMMENT '系统生成的数据源ID',
    source_name VARCHAR(120) NOT NULL COMMENT '数据源名称',
    source_type VARCHAR(20) NOT NULL COMMENT '数据源类型，当前仅MYSQL',
    connection_env_prefix VARCHAR(80) NOT NULL COMMENT '连接配置环境变量前缀，不存储密码',
    description VARCHAR(1000) NOT NULL DEFAULT '' COMMENT '数据源说明',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间（UTC）',
    updated_at DATETIME(3) NOT NULL COMMENT '更新时间（UTC）',
    PRIMARY KEY (source_id),
    KEY idx_source_created (created_at, source_id),
    CONSTRAINT chk_source_type CHECK (source_type = 'MYSQL')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='外部数据源登记，仅存连接引用及治理元数据';
