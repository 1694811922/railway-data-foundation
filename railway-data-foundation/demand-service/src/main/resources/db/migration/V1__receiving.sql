CREATE TABLE receiving_task (
 task_id VARCHAR(40) PRIMARY KEY COMMENT '接收任务编号',
 grant_id VARCHAR(40) NOT NULL UNIQUE COMMENT '底座订阅授权编号，每个授权一个任务',
 subject_id VARCHAR(40) NOT NULL COMMENT '真实登录身份关联的需求方主体',
 created_by VARCHAR(40) NOT NULL COMMENT '创建任务的真实账号编号',
 task_name VARCHAR(120) NOT NULL COMMENT '接收任务名称',
 context_json JSON NOT NULL COMMENT '服务端核验的连接和授权元信息，不含秘密',
 encrypted_secrets TEXT NOT NULL COMMENT '需求方独立密钥加密的Kafka密码与技术令牌',
 start_position VARCHAR(16) NOT NULL COMMENT '首次读取起点 EARLIEST或LATEST',
 desired_state VARCHAR(16) NOT NULL DEFAULT 'STOPPED' COMMENT '用户期望状态 RUNNING或STOPPED',
 runtime_state VARCHAR(20) NOT NULL DEFAULT 'STOPPED' COMMENT '实际状态 STOPPED STARTING RUNNING RETRYING BLOCKED',
 topic_identity VARCHAR(80) NULL COMMENT '首次核对的集群和主题身份，防止重建后误读',
 error_reason VARCHAR(240) NOT NULL DEFAULT '' COMMENT '固定中文错误原因，不记录业务内容或秘密',
 version INT NOT NULL DEFAULT 1 COMMENT '用户操作乐观锁版本',
 last_received_at DATETIME(3) NULL COMMENT '最近一次新增消息落库时间',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间 UTC',
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最近更新时间 UTC',
 INDEX idx_task_subject(subject_id,created_at)
) COMMENT '需求方真实接收任务，不在底座数据库中';
CREATE TABLE received_message (
 message_id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '落库消息编号',
 task_id VARCHAR(40) NOT NULL COMMENT '所属接收任务',
 grant_id VARCHAR(40) NOT NULL COMMENT '对应共享授权',
 topic_identity VARCHAR(80) NOT NULL COMMENT '集群和主题身份',
 topic VARCHAR(249) NOT NULL COMMENT '消息主题',
 partition_id INT NOT NULL COMMENT 'Kafka分区编号',
 message_offset BIGINT NOT NULL COMMENT 'Kafka分区内消息位置',
 message_key MEDIUMBLOB NULL COMMENT '原始消息键，NULL与空字节区分',
 message_value MEDIUMBLOB NULL COMMENT '完整原始消息内容，NULL代表空值消息',
 headers_json JSON NOT NULL COMMENT '有序消息头列表，值为Base64，保留同名头',
 kafka_timestamp BIGINT NOT NULL COMMENT 'Kafka时间戳毫秒，-1表示未知',
 timestamp_type VARCHAR(40) NOT NULL COMMENT 'Kafka时间戳类型',
 content_digest CHAR(64) NOT NULL COMMENT '原始消息及元信息SHA256摘要，用于重复一致性校验',
 payload_bytes INT NOT NULL COMMENT '消息内容字节数',
 received_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '需求方落库时间 UTC',
 UNIQUE KEY uk_delivery(grant_id,topic_identity,partition_id,message_offset),
 INDEX idx_message_task_time(task_id,received_at,message_id),
 FOREIGN KEY(task_id) REFERENCES receiving_task(task_id)
) COMMENT '需求方业务消息完整存储，底座不读取';
CREATE TABLE receiving_checkpoint (
 task_id VARCHAR(40) NOT NULL COMMENT '接收任务编号',
 partition_id INT NOT NULL COMMENT '主题分区编号',
 next_offset BIGINT NOT NULL COMMENT '数据库已持久化的下一消费位置',
 PRIMARY KEY(task_id,partition_id),
 FOREIGN KEY(task_id) REFERENCES receiving_task(task_id)
) COMMENT '需求方事务内消费检查点';
CREATE TABLE receiving_outbox (
 report_id CHAR(36) PRIMARY KEY COMMENT '固定摘要报告编号，重试保持一致',
 task_id VARCHAR(40) NOT NULL COMMENT '对应接收任务',
 report_json JSON NOT NULL COMMENT '技术摘要，仅分区位置字节，不包含业务值',
 report_state VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING待补报 SENT已确认 REJECTED授权拒绝',
 attempts INT NOT NULL DEFAULT 0 COMMENT '摘要传输尝试次数',
 next_attempt_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '下一补报时间 UTC',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '落库时间 UTC',
 FOREIGN KEY(task_id) REFERENCES receiving_task(task_id)
) COMMENT '需求方技术摘要持久补报队列';
