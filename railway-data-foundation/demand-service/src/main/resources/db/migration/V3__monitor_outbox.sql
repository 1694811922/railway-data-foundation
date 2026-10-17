CREATE TABLE receiving_monitor_outbox (
 log_id BIGINT PRIMARY KEY COMMENT '对应真实过程日志，与日志同事务加入队列',
 event_id CHAR(36) NOT NULL UNIQUE COMMENT '固定技术事件编号，补报重试不改变',
 report_state VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING待补报 SENT已确认 QUARANTINED格式冲突隔离',
 attempts INT NOT NULL DEFAULT 0 COMMENT '上报尝试次数',
 last_error VARCHAR(40) NOT NULL DEFAULT '' COMMENT '固定错误码，不保存异常文本或秘密',
 next_attempt_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '下次重试时间UTC',
 INDEX idx_monitor_pending(report_state,next_attempt_at),
 FOREIGN KEY(log_id) REFERENCES receiving_transfer_log(log_id) ON DELETE CASCADE
) COMMENT '管理员监测技术事件持久补报队列，独立于订阅消费摘要';
INSERT INTO receiving_monitor_outbox(log_id,event_id)
 SELECT log_id,UUID() FROM receiving_transfer_log;
