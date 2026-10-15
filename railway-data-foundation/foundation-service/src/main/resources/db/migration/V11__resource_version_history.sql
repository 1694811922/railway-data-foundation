CREATE TABLE resource_revision (
    resource_id VARCHAR(40) NOT NULL COMMENT '所属资源编号，与当前资源关联',
    version INT NOT NULL COMMENT '本次快照对应的资源版本，不补造以前版本',
    action VARCHAR(24) NOT NULL COMMENT 'BASELINE启用时基线、CREATE登记、RESOURCE_UPDATE信息修改、FIELD_UPDATE字段修改、PUBLISH发布',
    changed_field VARCHAR(64) NULL COMMENT '字段修改时的字段名，其他操作为空',
    recorded_at DATETIME(3) NOT NULL COMMENT '快照实际留存时间，UTC',
    resource_snapshot JSON NOT NULL COMMENT '该版本资源登记信息快照，不含连接信息或凭据',
    fields_snapshot JSON NOT NULL COMMENT '该版本全部资源字段技术与业务属性快照，不含业务行',
    PRIMARY KEY(resource_id,version),
    CONSTRAINT fk_revision_resource FOREIGN KEY(resource_id) REFERENCES data_resource(resource_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='资源版本历史；无更新接口，草稿资源删除时随资源清理';

-- Capture only the current state at adoption; the old versions were never retained.
INSERT INTO resource_revision(resource_id,version,action,changed_field,recorded_at,resource_snapshot,fields_snapshot)
SELECT r.resource_id,r.version,'BASELINE',NULL,UTC_TIMESTAMP(3),
 JSON_OBJECT('resource_id',r.resource_id,'resource_name',r.resource_name,'source_id',r.source_id,'source_name',s.source_name,
 'scan_task_id',r.scan_task_id,'schema_name',r.schema_name,'physical_table',r.physical_table,'resource_type',r.resource_type,
 'business_description',r.business_description,'owner_department',r.owner_department,'security_level',r.security_level,
 'update_mode',r.update_mode,'status',r.status,'version',r.version,
 'created_at',DATE_FORMAT(r.created_at,'%Y-%m-%dT%H:%i:%s.%f'),'updated_at',DATE_FORMAT(r.updated_at,'%Y-%m-%dT%H:%i:%s.%f')),
 COALESCE((SELECT JSON_ARRAYAGG(JSON_OBJECT('field_name',f.field_name,'data_type',f.data_type,
 'primary_key',JSON_EXTRACT(IF(f.primary_key,'true','false'),'$'),'technical_comment',f.technical_comment,
 'ordinal_position',f.ordinal_position,'business_name',f.business_name,'business_description',f.business_description,
 'time_field',JSON_EXTRACT(IF(f.time_field,'true','false'),'$'),'sensitivity_level',f.sensitivity_level,'resource_version',r.version))
 FROM resource_field f WHERE f.resource_id=r.resource_id),JSON_ARRAY())
FROM data_resource r JOIN source_system s ON s.source_id=r.source_id;
