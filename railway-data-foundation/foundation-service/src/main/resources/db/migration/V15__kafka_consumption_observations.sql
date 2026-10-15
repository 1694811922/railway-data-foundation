ALTER TABLE kafka_subscription_execution
    ADD consumption_token_digest CHAR(64) NULL COMMENT '一次性消费摘要上报令牌SHA-256摘要；绑定当前专属身份；不是Kafka或工作台凭据';

CREATE TABLE kafka_consumption_report (
    grant_id VARCHAR(40) NOT NULL COMMENT '订阅授权编号；主体、资源及能力从授权派生',
    report_id CHAR(36) NOT NULL COMMENT '消费端随机报告编号；同授权原内容重试只计一次',
    broker_username VARCHAR(64) NOT NULL COMMENT '当次已领取的专属Kafka身份快照；重新配置后旧报告保留',
    grant_version INT NOT NULL COMMENT '接收时授权版本；只记录、不增加授权版本',
    started_at DATETIME(3) NOT NULL COMMENT '消费端本次观察开始时间UTC；非业务处理开始证明',
    completed_at DATETIME(3) NOT NULL COMMENT '消费端观察结束时间UTC；非消费提交或业务完成证明',
    record_count INT NOT NULL COMMENT '本报告观察消息条次；不同报告重读相同位点仍计观察次数',
    byte_count BIGINT NOT NULL COMMENT '消息value原始字节合计；不含key、header或网络传输',
    positions_json JSON NOT NULL COMMENT '仅分区、位点及value字节数；不存key、value、消息头或业务字段',
    content_digest CHAR(64) NOT NULL COMMENT '规范化报告内容SHA-256校验；不包含明文上报令牌',
    received_at DATETIME(3) NOT NULL COMMENT '底座首次接收时间UTC；原内容重试不更新',
    PRIMARY KEY (grant_id,report_id),
    KEY idx_consumption_time (grant_id,completed_at,report_id),
    CONSTRAINT fk_consumption_grant FOREIGN KEY (grant_id) REFERENCES kafka_subscription_grant(grant_id),
    CONSTRAINT chk_consumption_counts CHECK (record_count BETWEEN 0 AND 100 AND byte_count BETWEEN 0 AND 134217728),
    CONSTRAINT chk_consumption_time CHECK (completed_at >= started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='外部Kafka消费者自报观察摘要；非Broker独立证明、业务交付或结算计量';
