CREATE TABLE receiving_transfer_log (
 log_id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '接收过程日志编号，按时间及编号排序',
 task_id VARCHAR(40) NOT NULL COMMENT '接收任务编号，通过任务继承需求方归属',
 batch_id CHAR(36) NULL COMMENT '落库与位置提交关联批次编号，不是消息内容',
 report_id CHAR(36) NULL COMMENT '关联技术摘要编号',
 event_type VARCHAR(32) NOT NULL COMMENT '固定事件类型，不存异常堆栈或业务内容',
 outcome VARCHAR(16) NOT NULL COMMENT 'SUCCESS INFO WARNING ERROR，落库和提交分别记录',
 reason_code VARCHAR(40) NOT NULL COMMENT '固定中文映射原因码，禁止原始异常文本',
 operator_id VARCHAR(64) NULL COMMENT '页面操作的真实账号编号，后台事件为空',
 record_count INT NULL COMMENT '本批实际读取条次，非业务唯一事件数',
 inserted_count INT NULL COMMENT '本批新增落库条数',
 duplicate_count INT NULL COMMENT '本批相同位置且内容一致的重读条数',
 byte_count BIGINT NULL COMMENT '本批消息值字节合计，不含消息头和键',
 positions_json JSON NULL COMMENT '分区起止及下一位置，不包含消息键或值',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '记录写入时间UTC',
 INDEX idx_transfer_task(task_id,created_at,log_id),
 INDEX idx_transfer_batch(task_id,batch_id,event_type),
 FOREIGN KEY(task_id) REFERENCES receiving_task(task_id) ON DELETE CASCADE
) COMMENT '需求方接收技术过程日志，不保存到统一底座';
