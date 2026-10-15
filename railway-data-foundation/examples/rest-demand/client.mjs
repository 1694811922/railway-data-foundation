// External caller example. No Source credentials, management writes, mock, or business persistence.
export class DemandError extends Error {
  constructor(kind, message, { status = null, code = null, traceId = null } = {}) {
    super(message); this.name = 'DemandError'; Object.assign(this, { kind, status, code, traceId });
  }
}
const fail = (message) => { throw new DemandError('CONFIG', message) };
const id = (value, prefix) => typeof value === 'string' && new RegExp(`^${prefix}_[A-Za-z0-9]{1,64}$`).test(value);
const tracePattern = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;
const fieldsAllowed = new Set(['id','container_no','event_type','event_time','port_code','yard_code','train_no','operation_status','equipment_no','source_system','created_at','updated_at']);
function trustedUrl(value, path) {
  let url; try { url = new URL(value) } catch { fail('服务地址格式不正确') }
  const local = ['127.0.0.1','localhost','[::1]'].includes(url.hostname);
  if (!(url.protocol === 'https:' || (url.protocol === 'http:' && local)) || url.username || url.password || url.search || url.hash || url.pathname !== path) {
    fail('服务地址须使用 HTTPS 或本机 HTTP，且不能包含账号、查询参数或额外路径');
  }
  return url;
}
function integer(value, min, max, label) {
  if (!Number.isInteger(value) || value < min || value > max) fail(`${label}超出允许范围`);
}
function time(value) {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,3})?(?:Z|[+-]\d{2}:\d{2})$/.test(value) || !Number.isFinite(Date.parse(value))) fail('数据时间须为带时区的 ISO 时间，最多毫秒精度');
  const date=value.slice(0,10), normalized=new Date(date+'T00:00:00Z');
  if (normalized.toISOString().slice(0,10)!==date || Number(value.slice(11,13))>23) fail('数据时间包含不存在的日期或时刻');
}
async function jsonRequest(url, headers, timeoutMs, signal, provider = false) {
  let response, traceId = null;
  try {
    response = await fetch(url, { method: 'GET', headers, redirect: 'manual', signal: signal ? AbortSignal.any([signal, AbortSignal.timeout(timeoutMs)]) : AbortSignal.timeout(timeoutMs) });
    const rawTrace = response.headers.get('x-trace-id');
    traceId = tracePattern.test(rawTrace || '') ? rawTrace : null;
    if (response.status >= 300 && response.status < 400) throw new DemandError('REDIRECT', '服务返回重定向，已停止请求', { status: response.status, traceId });
    const context = { status: response.status, traceId };
    if (!response.ok) {
      const labels = {400:'请求参数不合法',401:'调用凭据无效、已过期或已撤销',403:'共享状态或访问范围不允许本次请求',404:'资源或接口不存在',503:'授权控制或业务源暂不可用'};
      throw new DemandError('HTTP', labels[response.status] || '服务请求失败', context);
    }
    // Bound response memory even when the remote service omits Content-Length.
    const reader = response.body?.getReader();
    if (!reader) throw new DemandError('PROTOCOL', '服务响应为空', context);
    const chunks = []; let bytes = 0;
    try {
      while (true) {
        const { done, value } = await reader.read(); if (done) break;
        bytes += value.byteLength;
        if (bytes > 1024 * 1024) throw new DemandError('PROTOCOL', '单页响应超过示例的 1 MiB 限制', context);
        chunks.push(value);
      }
    } finally { await reader.cancel().catch(() => {}); reader.releaseLock() }
    let body;
    try { body = JSON.parse(Buffer.concat(chunks).toString('utf8')) } catch { throw new DemandError('PROTOCOL', '服务响应不是合法 JSON', context) }
    if (!body || !Number.isInteger(body.code) || typeof body.message !== 'string' || !Object.hasOwn(body, 'data')) throw new DemandError('PROTOCOL', '服务响应不符合统一接口约定', context);
    if (body.code !== 0) throw new DemandError('BUSINESS', '服务返回业务错误，已停止读取', { ...context, code: body.code });
    if (provider && !traceId) throw new DemandError('PROTOCOL', '提供方响应缺少有效追踪编号', context);
    return { data: body.data, traceId, responseBytes: bytes };
  } catch (error) {
    // Never forward raw transport errors, response text, tokens or business values to logs.
    if (error instanceof DemandError) throw error;
    throw new DemandError(signal?.aborted ? 'CANCELLED' : 'TRANSPORT', signal?.aborted ? '读取已取消' : '连接失败或超时，结果未确认；不会自动重试', { status: response?.status ?? null, traceId });
  } finally { if (response?.body && !response.body.locked) await response.body.cancel().catch(() => {}) }
}
export function createDemandClient({ foundationUrl = 'http://127.0.0.1:18080', providerOrigin = 'http://127.0.0.1:18081', capabilityId, relationshipId, token, timeoutMs = 5000 }) {
  const foundation = trustedUrl(foundationUrl, '/');
  const provider = trustedUrl(providerOrigin, '/');
  if (!id(capabilityId, 'CAP') || !id(relationshipId, 'REL')) fail('请填写实际能力编号与共享关系编号');
  if (typeof token !== 'string' || !/^rdf_[A-Za-z0-9_-]{43}$/.test(token)) fail('调用令牌格式不正确，请使用需求方凭据，不要使用数据库密码或服务间凭据');
  integer(timeoutMs, 1, 60000, '请求超时');
  let endpoint = null;
  async function discover({ signal } = {}) {
    endpoint = null;
    const result = await jsonRequest(new URL(`/api/v1/capabilities/${capabilityId}/invocation`, foundation), { Authorization: `Bearer ${token}`, 'X-Relationship-Id': relationshipId }, timeoutMs, signal);
    const value = result.data;
    if (!value || value.capability_id !== capabilityId || value.http_method !== 'GET' || value.endpoint !== '/containers/events' || typeof value.enabled !== 'boolean') throw new DemandError('PROTOCOL', '调用说明与所选能力不一致');
    let target;
    try { target = new URL(value.request_url) } catch { throw new DemandError('PROTOCOL', '调用说明缺少有效提供方地址') }
    if (target.origin !== provider.origin || target.pathname !== '/containers/events' || target.username || target.password || target.search || target.hash) throw new DemandError('PROTOCOL', '调用说明与明确配置的提供方地址不一致，已阻止发送令牌');
    if (!value.enabled) throw new DemandError('UNAVAILABLE', '接口能力当前不可用，请由管理员检查');
    endpoint = target.toString();
    return { capabilityId, endpoint, enabled: true };
  }
  async function queryPage({ fields, from, to, limit = 20, offset = 0, signal } = {}) {
    integer(limit, 1, 100, '每页数量'); integer(offset, 0, 100000, '分页偏移');
    if (fields !== undefined && (!Array.isArray(fields) || fields.length === 0 || fields.length > 12 || new Set(fields).size !== fields.length || fields.some(f => !fieldsAllowed.has(f)))) fail('请求字段须为不重复的集装箱事件字段名，不能使用通配符或 SQL');
    if (from !== undefined) time(from); if (to !== undefined) time(to);
    if (from !== undefined && to !== undefined && Date.parse(from) >= Date.parse(to)) fail('数据开始时间须早于结束时间');
    if (!endpoint) throw new DemandError('CONFIG', '请先获取并核对调用说明');
    const url = new URL(endpoint); url.searchParams.set('limit', limit); url.searchParams.set('offset', offset);
    if (fields !== undefined) url.searchParams.set('fields', fields.join(','));
    if (from !== undefined) url.searchParams.set('from', from); if (to !== undefined) url.searchParams.set('to', to);
    const result = await jsonRequest(url, { Authorization: `Bearer ${token}`, 'X-Relationship-Id': relationshipId, 'X-Capability-Id': capabilityId }, timeoutMs, signal, true);
    const context = { status: 200, traceId: result.traceId };
    if (!Array.isArray(result.data) || result.data.length > limit || result.data.some(row => !row || typeof row !== 'object' || Array.isArray(row) || Object.keys(row).length === 0 || Object.keys(row).some(key => !fieldsAllowed.has(key)) || Object.values(row).some(value => value !== null && !['string','number','boolean'].includes(typeof value)) || (fields !== undefined && (Object.keys(row).length !== fields.length || fields.some(field => !Object.hasOwn(row, field)))))) throw new DemandError('PROTOCOL', '提供方返回字段或分页结构不符合请求约定，未交付业务结果', context);
    return { rows: result.data, traceId: result.traceId, responseBytes: result.responseBytes, offset };
  }
  async function consume({ pageSize = 20, maxPages = 5, onPage, signal, ...query } = {}) {
    integer(pageSize, 1, 100, '每页数量'); integer(maxPages, 1, 1000, '最大页数');
    if (Object.keys(query).some(key => !['fields','from','to'].includes(key))) fail('批量读取只接受字段和数据时间范围');
    if (onPage !== undefined && typeof onPage !== 'function') fail('页面处理器必须为函数');
    const observations = []; let recordCount = 0;
    for (let page = 0; page < maxPages; page++) {
      if (signal?.aborted) throw new DemandError('CANCELLED', '读取已取消');
      const result = await queryPage({ ...query, limit: pageSize, offset: page * pageSize, signal });
      if (signal?.aborted) throw new DemandError('CANCELLED', '读取已取消');
      if (onPage) await onPage(result.rows, { traceId: result.traceId, offset: result.offset });
      recordCount += result.rows.length;
      observations.push({ traceId: result.traceId, recordCount: result.rows.length, responseBytes: result.responseBytes, offset: result.offset });
      if (result.rows.length < pageSize) return { recordCount, observations, limitReached: false };
    }
    return { recordCount, observations, limitReached: true };
  }
  return { discover, queryPage, consume };
}
