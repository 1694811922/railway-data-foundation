-- Explain the stored metadata in Navicat and other database clients.
-- Keep each existing type, nullability and default unchanged; only add column comments.

ALTER TABLE source_system
    MODIFY `host` VARCHAR(253) NULL COMMENT '外部数据库主机名或IPv4地址',
    MODIFY `port` INT NULL COMMENT '外部数据库监听端口，范围1至65535',
    MODIFY `database_name` VARCHAR(64) NULL COMMENT '外部数据库名称，用于建立连接和限定扫描范围',
    MODIFY `username` VARCHAR(128) NULL COMMENT '外部数据库登录用户名；不包含密码',
    MODIFY `ssl_mode` VARCHAR(20) NULL COMMENT '连接加密方式，如DISABLED、PREFERRED或REQUIRED',
    MODIFY `credential_ref` VARCHAR(40) NULL COMMENT '凭据引用编号，外键关联source_credential.credential_ref；旧配置可为空',
    MODIFY `status` VARCHAR(20) NOT NULL DEFAULT 'ENABLED' COMMENT '数据源状态：ENABLED启用，DISABLED停用';

ALTER TABLE source_credential
    MODIFY `credential_ref` VARCHAR(40) NOT NULL COMMENT '凭据引用编号，供source_system.credential_ref关联；不是密码',
    MODIFY `username` VARCHAR(128) NOT NULL COMMENT '与凭据配套的外部数据库登录用户名',
    MODIFY `updated_at` DATETIME(3) NOT NULL COMMENT '凭据最后更新时间（UTC）';

ALTER TABLE metadata_scan_task
    MODIFY `task_id` VARCHAR(40) NOT NULL COMMENT '元数据扫描任务编号',
    MODIFY `source_id` VARCHAR(40) NOT NULL COMMENT '被扫描的数据源编号，关联source_system.source_id',
    MODIFY `status` VARCHAR(16) NOT NULL COMMENT '扫描状态：PENDING、RUNNING、SUCCESS或FAILED',
    MODIFY `table_count` INT NOT NULL DEFAULT 0 COMMENT '本次扫描发现的数据表数量',
    MODIFY `started_at` DATETIME(3) NOT NULL COMMENT '扫描开始时间（UTC）',
    MODIFY `finished_at` DATETIME(3) NULL COMMENT '扫描结束时间（UTC），未结束时为空',
    MODIFY `failure_reason` VARCHAR(300) NULL COMMENT '扫描失败的安全提示，成功时为空';

ALTER TABLE metadata_scan_table
    MODIFY `task_id` VARCHAR(40) NOT NULL COMMENT '所属扫描任务编号',
    MODIFY `schema_name` VARCHAR(64) NOT NULL COMMENT '外部数据库或Schema名称',
    MODIFY `table_name` VARCHAR(64) NOT NULL COMMENT '扫描发现的外部物理表名',
    MODIFY `table_comment` VARCHAR(1000) NOT NULL DEFAULT '' COMMENT '源表的技术备注',
    MODIFY `estimated_rows` BIGINT NULL COMMENT '源数据库给出的估算行数，并非精确统计',
    MODIFY `field_count` INT NOT NULL COMMENT '扫描发现的字段数量';

ALTER TABLE metadata_scan_field
    MODIFY `task_id` VARCHAR(40) NOT NULL COMMENT '所属扫描任务编号',
    MODIFY `schema_name` VARCHAR(64) NOT NULL COMMENT '字段所在外部数据库或Schema名称',
    MODIFY `table_name` VARCHAR(64) NOT NULL COMMENT '字段所属的外部物理表名',
    MODIFY `field_name` VARCHAR(64) NOT NULL COMMENT '源表中的技术字段名',
    MODIFY `data_type` VARCHAR(64) NOT NULL COMMENT '字段基础数据类型，如varchar或datetime',
    MODIFY `column_type` VARCHAR(255) NOT NULL COMMENT '字段完整类型定义，如varchar(64)',
    MODIFY `nullable` BOOLEAN NOT NULL COMMENT '源字段是否允许为空：1是，0否',
    MODIFY `primary_key` BOOLEAN NOT NULL COMMENT '源字段是否为主键：1是，0否',
    MODIFY `default_value` VARCHAR(1000) NULL COMMENT '源字段默认值；没有默认值时为空',
    MODIFY `technical_comment` VARCHAR(1000) NOT NULL DEFAULT '' COMMENT '源字段原有的技术备注',
    MODIFY `ordinal_position` INT NOT NULL COMMENT '字段在源表中的顺序，从1开始';

ALTER TABLE data_resource
    MODIFY `resource_id` VARCHAR(40) NOT NULL COMMENT '底座生成的数据资源编号',
    MODIFY `source_id` VARCHAR(40) NOT NULL COMMENT '资源所属数据源编号，关联source_system.source_id',
    MODIFY `scan_task_id` VARCHAR(40) NOT NULL COMMENT '资源依据的成功扫描任务编号',
    MODIFY `schema_name` VARCHAR(64) NOT NULL COMMENT '对应源数据库或Schema名称',
    MODIFY `physical_table` VARCHAR(64) NOT NULL COMMENT '对应源数据库的物理表名',
    MODIFY `resource_name` VARCHAR(120) NOT NULL COMMENT '人工确认的资源名称',
    MODIFY `resource_type` VARCHAR(20) NOT NULL DEFAULT 'TABLE' COMMENT '资源类型，当前为TABLE',
    MODIFY `business_description` VARCHAR(2000) NOT NULL COMMENT '人工填写的资源业务描述',
    MODIFY `owner_department` VARCHAR(120) NOT NULL COMMENT '资源责任单位',
    MODIFY `security_level` VARCHAR(20) NOT NULL COMMENT '资源安全等级',
    MODIFY `update_mode` VARCHAR(20) NOT NULL COMMENT '资源数据更新方式',
    MODIFY `status` VARCHAR(20) NOT NULL DEFAULT 'DRAFT' COMMENT '资源状态：DRAFT草稿或PUBLISHED已发布',
    MODIFY `version` INT NOT NULL DEFAULT 1 COMMENT '资源元数据版本号',
    MODIFY `created_at` DATETIME(3) NOT NULL COMMENT '资源登记时间（UTC）',
    MODIFY `updated_at` DATETIME(3) NOT NULL COMMENT '资源最后更新时间（UTC）';

ALTER TABLE resource_field
    MODIFY `resource_id` VARCHAR(40) NOT NULL COMMENT '所属数据资源编号',
    MODIFY `field_name` VARCHAR(64) NOT NULL COMMENT '从源表扫描得到的技术字段名',
    MODIFY `data_type` VARCHAR(64) NOT NULL COMMENT '从源表扫描得到的基础数据类型',
    MODIFY `primary_key` BOOLEAN NOT NULL COMMENT '源字段是否为主键：1是，0否',
    MODIFY `technical_comment` VARCHAR(1000) NOT NULL DEFAULT '' COMMENT '扫描得到的源字段技术备注',
    MODIFY `ordinal_position` INT NOT NULL COMMENT '字段在源表中的顺序，从1开始',
    MODIFY `business_name` VARCHAR(120) NOT NULL DEFAULT '' COMMENT '人工填写的字段业务名称',
    MODIFY `business_description` VARCHAR(1000) NOT NULL DEFAULT '' COMMENT '人工填写的字段业务描述',
    MODIFY `time_field` BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否为业务时间字段：1是，0否',
    MODIFY `sensitivity_level` VARCHAR(20) NOT NULL DEFAULT '' COMMENT '人工确认的字段敏感级别';

ALTER TABLE capability
    MODIFY `capability_id` VARCHAR(40) NOT NULL COMMENT '底座生成的接口能力编号',
    MODIFY `resource_id` VARCHAR(40) NOT NULL COMMENT '能力所属资源编号，关联data_resource.resource_id',
    MODIFY `capability_name` VARCHAR(120) NOT NULL COMMENT '人工登记的能力名称',
    MODIFY `type` VARCHAR(24) NOT NULL COMMENT '能力类型：REST_API、KAFKA_TOPIC、CDC_STREAM或READONLY_QUERY',
    MODIFY `http_method` VARCHAR(8) NOT NULL DEFAULT '' COMMENT 'REST接口的HTTP方法，其他类型为空',
    MODIFY `endpoint` VARCHAR(500) NOT NULL DEFAULT '' COMMENT 'REST接口访问地址或路径，其他类型为空',
    MODIFY `topic` VARCHAR(249) NOT NULL DEFAULT '' COMMENT 'Kafka或CDC能力关联的Topic名称',
    MODIFY `source_table` VARCHAR(64) NOT NULL DEFAULT '' COMMENT 'CDC能力关联的源表名',
    MODIFY `protocol` VARCHAR(40) NOT NULL DEFAULT '' COMMENT '接口或消息访问协议',
    MODIFY `auth_type` VARCHAR(24) NOT NULL DEFAULT '' COMMENT '能力登记的认证方式；不保存认证凭据',
    MODIFY `message_schema` TEXT NULL COMMENT '消息字段结构说明，不保存实际消息',
    MODIFY `query_template_code` VARCHAR(120) NOT NULL DEFAULT '' COMMENT '只读查询模板的代码或编号',
    MODIFY `description` VARCHAR(1000) NOT NULL DEFAULT '' COMMENT '能力用途和访问说明',
    MODIFY `status` VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT '能力生命周期状态：草稿、已验证、启用、停用或退役',
    MODIFY `health_status` VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN' COMMENT '能力健康状态：未知、正常、异常或降级',
    MODIFY `version` INT NOT NULL DEFAULT 1 COMMENT '能力元数据版本号',
    MODIFY `created_at` DATETIME(3) NOT NULL COMMENT '能力登记时间（UTC）',
    MODIFY `updated_at` DATETIME(3) NOT NULL COMMENT '能力最后更新时间（UTC）';
