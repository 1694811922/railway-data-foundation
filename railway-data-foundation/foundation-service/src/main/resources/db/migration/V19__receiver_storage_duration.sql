ALTER TABLE kafka_receiver_event
 ADD COLUMN storage_duration_ms BIGINT NULL COMMENT '接收端单调时钟测量的批次入库事务至提交返回耗时毫秒，仅位置回执事件携带；历史未知不回填';
