// Contract fixtures for tests only. Production runtime pages never use these values.
export const metadataId=prefix=>prefix+'_'+'a'.repeat(32)
export function event(index=1,changes={}){
  const uuid=n=>'00000000-0000-4000-8000-'+n.toString(16).padStart(12,'0')
  return {event_id:uuid(index),report_id:uuid(1000+index),trace_id:uuid(2000+index),collector_id:'provider-local',collection_point:'REST_PROVIDER',relationship_id:metadataId('REL'),capability_id:metadataId('CAP'),subject_id:metadataId('SUB'),resource_id:metadataId('RES'),policy_id:metadataId('POL'),relationship_version:4,policy_version:1,started_at:'2026-10-06T01:00:01Z',completed_at:'2026-10-06T01:00:02Z',received_at:'2026-10-06T01:00:03Z',outcome:'SUCCESS',reason_code:'COMPLETED',http_status:200,record_count:2,byte_count:148,duration_ms:1000,topic:null,topic_partition:null,topic_offset:null,...changes}
}
export function window(start,state='CLOSED',point='REST_PROVIDER',changes={}){
  const known=state==='CLOSED'||state==='PARTIAL'
  return {window_start:start,window_until:new Date(Date.parse(start)+60000).toISOString(),collection_point:point,state,unit:point==='REST_PROVIDER'?'CALL':'MESSAGE',attempts:known?0:null,calls:point==='REST_PROVIDER'&&known?0:null,messages:point==='KAFKA_PRODUCER'&&known?0:null,successes:known?0:null,denied:known?0:null,errors:known?0:null,records:known?0:null,bytes:known?0:null,average_duration_ms:null,frequency_per_second:known?0:null,reports:state==='MISSING'?0:1,late_reports:0,maximum_report_delay_ms:0,...changes}
}
