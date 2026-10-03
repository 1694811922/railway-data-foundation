CREATE TABLE metadata_scan_task (
    task_id VARCHAR(40) PRIMARY KEY,
    source_id VARCHAR(40) NOT NULL,
    status VARCHAR(16) NOT NULL,
    table_count INT NOT NULL DEFAULT 0,
    started_at DATETIME(3) NOT NULL,
    finished_at DATETIME(3) NULL,
    failure_reason VARCHAR(300) NULL,
    KEY idx_scan_source_time (source_id, started_at),
    CONSTRAINT fk_scan_source FOREIGN KEY (source_id) REFERENCES source_system(source_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='元数据扫描任务';

CREATE TABLE metadata_scan_table (
    task_id VARCHAR(40) NOT NULL,
    schema_name VARCHAR(64) NOT NULL,
    table_name VARCHAR(64) NOT NULL,
    table_comment VARCHAR(1000) NOT NULL DEFAULT '',
    estimated_rows BIGINT NULL,
    field_count INT NOT NULL,
    PRIMARY KEY (task_id, schema_name, table_name),
    CONSTRAINT fk_scan_table_task FOREIGN KEY (task_id) REFERENCES metadata_scan_task(task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='仅保存发现的表结构，不保存业务行';

CREATE TABLE metadata_scan_field (
    task_id VARCHAR(40) NOT NULL,
    schema_name VARCHAR(64) NOT NULL,
    table_name VARCHAR(64) NOT NULL,
    field_name VARCHAR(64) NOT NULL,
    data_type VARCHAR(64) NOT NULL,
    column_type VARCHAR(255) NOT NULL,
    nullable BOOLEAN NOT NULL,
    primary_key BOOLEAN NOT NULL,
    default_value VARCHAR(1000) NULL,
    technical_comment VARCHAR(1000) NOT NULL DEFAULT '',
    ordinal_position INT NOT NULL,
    PRIMARY KEY (task_id, schema_name, table_name, field_name),
    CONSTRAINT fk_scan_field_table FOREIGN KEY (task_id, schema_name, table_name)
        REFERENCES metadata_scan_table(task_id, schema_name, table_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='扫描发现的技术字段';

CREATE TABLE data_resource (
    resource_id VARCHAR(40) PRIMARY KEY,
    source_id VARCHAR(40) NOT NULL,
    scan_task_id VARCHAR(40) NOT NULL,
    schema_name VARCHAR(64) NOT NULL,
    physical_table VARCHAR(64) NOT NULL,
    resource_name VARCHAR(120) NOT NULL,
    resource_type VARCHAR(20) NOT NULL DEFAULT 'TABLE',
    business_description VARCHAR(2000) NOT NULL,
    owner_department VARCHAR(120) NOT NULL,
    security_level VARCHAR(20) NOT NULL,
    update_mode VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    version INT NOT NULL DEFAULT 1,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_resource_table (source_id, schema_name, physical_table),
    KEY idx_resource_updated (updated_at),
    CONSTRAINT fk_resource_source FOREIGN KEY (source_id) REFERENCES source_system(source_id),
    CONSTRAINT fk_resource_scan FOREIGN KEY (scan_task_id, schema_name, physical_table)
        REFERENCES metadata_scan_table(task_id, schema_name, table_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='人工确认后的数据资源登记';

CREATE TABLE resource_field (
    resource_id VARCHAR(40) NOT NULL,
    field_name VARCHAR(64) NOT NULL,
    data_type VARCHAR(64) NOT NULL,
    primary_key BOOLEAN NOT NULL,
    technical_comment VARCHAR(1000) NOT NULL DEFAULT '',
    ordinal_position INT NOT NULL,
    business_name VARCHAR(120) NOT NULL DEFAULT '',
    business_description VARCHAR(1000) NOT NULL DEFAULT '',
    time_field BOOLEAN NOT NULL DEFAULT FALSE,
    sensitivity_level VARCHAR(20) NOT NULL DEFAULT '',
    PRIMARY KEY (resource_id, field_name),
    CONSTRAINT fk_resource_field_resource FOREIGN KEY (resource_id) REFERENCES data_resource(resource_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='技术字段与人工确认的业务属性';
