import { createDemandClient, DemandError } from './client.mjs';

const controller = new AbortController();
process.once('SIGINT', () => controller.abort());
try {
  const client = createDemandClient({
    foundationUrl: process.env.DEMAND_FOUNDATION_URL,
    providerOrigin: process.env.DEMAND_PROVIDER_ORIGIN,
    capabilityId: process.env.DEMAND_CAPABILITY_ID,
    relationshipId: process.env.DEMAND_RELATIONSHIP_ID,
    token: process.env.DEMAND_CALLER_TOKEN
  });
  // Keep the caller secret only in the client closure for this invocation.
  delete process.env.DEMAND_CALLER_TOKEN;
  await client.discover({ signal: controller.signal });
  const summary = await client.consume({
    fields: process.env.DEMAND_FIELDS ? process.env.DEMAND_FIELDS.split(',') : undefined,
    from: process.env.DEMAND_FROM || undefined, to: process.env.DEMAND_TO || undefined,
    pageSize: Number(process.env.DEMAND_PAGE_SIZE || 20), maxPages: Number(process.env.DEMAND_MAX_PAGES || 5),
    signal: controller.signal,
    // This example discards rows after each page. Integrators can supply their own onPage handler.
    onPage(rows, meta) { console.log(`已读取 ${rows.length} 条；追踪编号 ${meta.traceId}`) }
  });
  console.log(`本次已观察 ${summary.recordCount} 条，${summary.observations.length} 次请求。`);
  if (summary.limitReached) console.log('已达到读取页数上限，无法确认是否还有数据。');
} catch (error) {
  const safe = error instanceof DemandError ? error : new DemandError('CLIENT', '调用方处理失败，已停止读取');
  console.error(`${safe.message}${safe.status ? `（HTTP ${safe.status}）` : ''}${safe.traceId ? `；追踪编号 ${safe.traceId}` : ''}`);
  process.exitCode = 1;
} finally { delete process.env.DEMAND_CALLER_TOKEN }
