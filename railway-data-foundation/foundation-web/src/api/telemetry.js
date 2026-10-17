import {request} from '../utils/request'
export const telemetryApi={
  transfers:params=>request.get('/transfer-monitor/events',{params,quiet:true}),
  receiverEvents:params=>request.get('/kafka-transfer-events',{params,quiet:true}),
  events:params=>request.get('/runtime-events',{params}),
  event:id=>request.get(`/runtime-events/${encodeURIComponent(id)}`),
  trace:id=>request.get(`/runtime-traces/${encodeURIComponent(id)}`),
  windows:params=>request.get('/metrics/windows',{params}),
  collector:()=>request.get('/runtime-collector'),
}
