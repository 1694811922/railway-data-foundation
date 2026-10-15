CREATE TABLE kafka_subscription_execution (
    grant_id VARCHAR(40) NOT NULL COMMENT '订阅审批编号；每个申请当前仅一份独立身份',
    broker_username VARCHAR(64) NOT NULL COMMENT '本轮执行专属随机Kafka主体；不能由客户端指定或复用',
    cluster_id VARCHAR(100) NOT NULL COMMENT '运维明确配置并实际核对的Kafka集群编号；防止向其他集群撤权',
    desired_state VARCHAR(16) NOT NULL COMMENT '目标状态：GRANTED配置读取、REVOKED移除权限和身份',
    execution_state VARCHAR(24) NOT NULL COMMENT '实际执行状态：PENDING、ACTIVE、REVOKE_PENDING、REVOKED、FAILED；失败为未知',
    encrypted_password TEXT NULL COMMENT '待一次性领取的AES-GCM密文；绑定申请及主体；领取或撤权核对后清除',
    issued_at DATETIME(3) NULL COMMENT '一次性领取时间UTC；领取成功后普通查询仍不返回密码',
    last_error VARCHAR(200) NOT NULL DEFAULT '' COMMENT '固定脱敏失败原因；不保存底层异常或凭据',
    checked_at DATETIME(3) NULL COMMENT '最近实际执行或核对时间UTC；不是持续实时保证',
    updated_at DATETIME(3) NOT NULL COMMENT '执行记录修改时间UTC',
    PRIMARY KEY (grant_id),
    UNIQUE KEY uk_kafka_execution_identity (broker_username),
    KEY idx_kafka_execution_check (checked_at,grant_id),
    CONSTRAINT fk_kafka_execution_grant FOREIGN KEY (grant_id) REFERENCES kafka_subscription_grant(grant_id),
    CONSTRAINT chk_kafka_execution_desired CHECK (desired_state IN ('GRANTED','REVOKED')),
    CONSTRAINT chk_kafka_execution_state CHECK (execution_state IN ('PENDING','ACTIVE','REVOKE_PENDING','REVOKED','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Kafka专属订阅身份及权限协调；不存业务消息；数据库与Broker不是原子事务';

ALTER TABLE kafka_subscription_event
    MODIFY action VARCHAR(32) NOT NULL COMMENT '审批或执行操作；执行申请、实际配置、停止、撤权、失败、一次性领取等',
    ADD execution_state VARCHAR(24) NULL COMMENT '当次操作后的执行状态；历史审批记录可为空';
