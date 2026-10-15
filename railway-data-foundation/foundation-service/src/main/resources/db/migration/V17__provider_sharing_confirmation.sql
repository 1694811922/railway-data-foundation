-- 存量申请保留原管理员审批流程，不补造提供方确认或改写历史。
ALTER TABLE sharing_relationship
    ADD COLUMN provider_review_mode VARCHAR(24) NOT NULL DEFAULT 'LEGACY' COMMENT '提供方确认模式：REQUIRED新增有归属申请需确认、ADMIN_MANAGED新增无归属由管理员处理、LEGACY存量原流程',
    ADD COLUMN provider_confirmed_at DATETIME(3) NULL COMMENT '提供方确认共享内容的时间UTC；提交或退回修改时清空，操作者记录在历史表',
    ADD CONSTRAINT chk_relationship_provider_review CHECK (provider_review_mode IN ('REQUIRED','ADMIN_MANAGED','LEGACY'));
ALTER TABLE kafka_subscription_grant
    ADD COLUMN provider_review_mode VARCHAR(24) NOT NULL DEFAULT 'LEGACY' COMMENT '提供方确认模式：REQUIRED新增有归属申请需确认、ADMIN_MANAGED新增无归属由管理员处理、LEGACY存量原流程',
    ADD COLUMN provider_confirmed_at DATETIME(3) NULL COMMENT '提供方确认整主题完整消息及历史范围的时间UTC；提交或退回修改时清空，操作者记录在历史表',
    ADD CONSTRAINT chk_kafka_provider_review CHECK (provider_review_mode IN ('REQUIRED','ADMIN_MANAGED','LEGACY'));
