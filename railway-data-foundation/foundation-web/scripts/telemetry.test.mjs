import assert from 'node:assert/strict'
import test from 'node:test'
import {filterForm,defaultRange,runtimeQuery,assertEvent,eventPage,traceEvents,metricWindows,metricSummary,collectorStatus,quantity} from '../src/utils/telemetry.js'
import {event,window,metadataId} from './telemetry-fixtures.mjs'
const params={collectionPoint:'REST_PROVIDER',from:'2026-10-06T01:00:00Z',until:'2026-10-06T01:01:00Z'}
test('runtime filters preserve server scope and use complete UTC minutes',()=>{
  assert.deepEqual(defaultRange(Date.parse('2026-10-06T01:15:44Z')).map(d=>d.toISOString()),['2026-10-06T01:00:00.000Z','2026-10-06T01:15:00.000Z'])
  const form={...filterForm(true),scopeType:'relationshipId',scopeId:' '+metadataId('REL')+' ',range:[params.from,params.until]}
  assert.deepEqual(runtimeQuery(form,true),{...params,from:'2026-10-06T01:00:00.000Z',until:'2026-10-06T01:01:00.000Z',relationshipId:metadataId('REL')})
  assert.throws(()=>runtimeQuery({...form,scopeId:metadataId('CAP')},true))
  assert.throws(()=>runtimeQuery({...form,range:[params.from,'2026-10-07T01:01:00Z']},true))
  assert.throws(()=>runtimeQuery({...form,range:['2026-10-06T01:00:01Z',params.until]},true))
  assert.throws(()=>runtimeQuery({...form,range:null},true))
})
test('audit filters validate UUID and outcome, without inventing actor identity',()=>{
  const form={...filterForm(),outcome:'DENIED',traceId:event().trace_id}
  assert.deepEqual(runtimeQuery(form),{collectionPoint:'REST_PROVIDER',outcome:'DENIED',traceId:event().trace_id})
  assert.throws(()=>runtimeQuery({...form,traceId:'not-a-trace'}))
  assert.throws(()=>runtimeQuery({...form,outcome:'ALLOWED'}))
  assert.throws(()=>runtimeQuery({...form,range:[params.until,params.from]}))
})
test('event reads reject foreign scope, wrong IDs, secret payloads and inconsistent success',()=>{
  assert.equal(assertEvent(event(),{id:event().event_id,params:{...params,relationshipId:metadataId('REL')}}).record_count,2)
  assert.throws(()=>assertEvent(event(),{id:event(2).event_id}))
  assert.throws(()=>assertEvent(event(),{params:{resourceId:metadataId('CAP')}}))
  for(const change of [{token:'secret'},{business_rows:[]},{subject_id:null},{policy_version:null},{record_count:1.2},{http_status:202},{reason_code:'INTERNAL_ERROR'},{topic_offset:1}])assert.throws(()=>assertEvent(event(1,change)))
})
test('unauthenticated denials are valid history with zero sharing volume and unknown identity',()=>{
  const denied=event(1,{outcome:'DENIED',reason_code:'IDENTITY_INVALID',http_status:401,record_count:0,byte_count:0,relationship_id:null,capability_id:null,subject_id:null,resource_id:null,policy_id:null,relationship_version:null,policy_version:null})
  assert.equal(assertEvent(denied).subject_id,null)
  assert.throws(()=>assertEvent({...denied,byte_count:1}))
  assert.throws(()=>assertEvent({...denied,http_status:200}))
  assert.equal(assertEvent(event(1,{outcome:'ERROR',reason_code:'SERIALIZATION_ERROR',http_status:200,record_count:0,byte_count:0})).outcome,'ERROR')
})
test('Kafka success requires acknowledgement and counts one emitted message',()=>{
  const sent=event(1,{collection_point:'KAFKA_PRODUCER',record_count:1,topic:'port.container.event.v1',topic_partition:0,topic_offset:12})
  assert.equal(assertEvent(sent).topic_offset,12)
  assert.throws(()=>assertEvent({...sent,topic_offset:null}))
  assert.throws(()=>assertEvent({...sent,record_count:2}))
})
test('audit pagination and trace responses reject duplicate or unrelated events',()=>{
  const pageParams={...params,limit:20,offset:0},data={rows:[event()],total:1,limit:20,offset:0}
  assert.equal(eventPage(data,pageParams).total,1)
  assert.throws(()=>eventPage({...data,offset:20},pageParams))
  assert.throws(()=>eventPage({...data,rows:[event(),event()],total:2},pageParams))
  assert.throws(()=>eventPage(data,{...pageParams,offset:20}))
  assert.equal(traceEvents([event()],event().trace_id).length,1)
  assert.throws(()=>traceEvents([event(2)],event().trace_id))
})
test('minute metrics keep unknown separate from confirmed zero and partial lower bounds',()=>{
  const missing=window(params.from,'MISSING'),closed=window(params.from)
  assert.equal(metricWindows([missing],params)[0].calls,null)
  assert.equal(metricSummary([missing],'REST_PROVIDER')[0].value,null)
  assert.equal(quantity(null),'—')
  assert.equal(metricSummary(metricWindows([closed],params),'REST_PROVIDER')[0].value,0)
  const partial=window(params.from,'PARTIAL','REST_PROVIDER',{attempts:3,calls:3,successes:2,denied:1,records:5,bytes:400,average_duration_ms:20,frequency_per_second:3/60})
  const summary=metricSummary(metricWindows([partial],params),'REST_PROVIDER')
  assert.equal(summary[0].value,3);assert.equal(summary[0].partial,true);assert.equal(summary[4].value,5)
  assert.equal(metricSummary([closed,missing],'REST_PROVIDER')[0].partial,true)
})
test('window units, ordering, counters and coverage must match requested scope',()=>{
  const valid=window(params.from)
  for(const change of [{calls:1},{successes:1},{messages:0},{state:'UNKNOWN'},{frequency_per_second:1},{reports:0},{window_start:params.until},{unit:'MESSAGE'}])assert.throws(()=>metricWindows([{...valid,...change}],params))
  assert.throws(()=>metricWindows([],params))
  assert.throws(()=>metricWindows([window(params.from,'MISSING','REST_PROVIDER',{bytes:0})],params))
})
test('message production metrics use successful messages, not REST calls or send attempts',()=>{
  const kafka=window(params.from,'PARTIAL','KAFKA_PRODUCER',{attempts:4,messages:3,successes:3,errors:1,records:3,bytes:600,average_duration_ms:10,frequency_per_second:3/60})
  const data=metricWindows([kafka],{...params,collectionPoint:'KAFKA_PRODUCER'})
  assert.equal(metricSummary(data,'KAFKA_PRODUCER')[0].value,3)
  assert.throws(()=>metricWindows([{...kafka,messages:4}],{...params,collectionPoint:'KAFKA_PRODUCER'}))
  assert.throws(()=>metricSummary([{...kafka,messages:Number.MAX_SAFE_INTEGER},kafka],'KAFKA_PRODUCER'))
})
test('collector status needs explicit readiness and nonnegative operational counters',()=>{
  const status={enabled:true,storage_ready:true,last_success_at:null,...Object.fromEntries('queued_events staged_reports pending_reports quarantined_reports submitted_events dropped_events spool_failures delivery_failures rejected_reports delivered_reports'.split(' ').map(key=>[key,0]))}
  assert.equal(collectorStatus(status).enabled,true)
  assert.throws(()=>collectorStatus({...status,pending_reports:-1}))
  assert.throws(()=>collectorStatus({...status,storage_ready:'true'}))
})
