ALTER TABLE receiving_transfer_log
 ADD COLUMN storage_duration_ms BIGINT NULL COMMENT '批次入库事务开始至提交返回耗时毫秒，不含Kafka等待和位置提交；回执缺失或旧记录为未知';
