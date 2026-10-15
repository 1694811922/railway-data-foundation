import {request} from '../utils/request'
export const telemetryApi={
  events:params=>request.get('/runtime-events',{params}),
  event:id=>request.get(`/runtime-events/${encodeURIComponent(id)}`),
  trace:id=>request.get(`/runtime-traces/${encodeURIComponent(id)}`),
  windows:params=>request.get('/metrics/windows',{params}),
  collector:()=>request.get('/runtime-collector'),
}
