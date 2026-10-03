import { test } from 'node:test'
import assert from 'node:assert/strict'
import { validateCapability, invocationCommand } from '../src/utils/capabilityForm.js'

const rest = { resource_id: 'RES_test', capability_name: '查询接口', type: 'REST_API', endpoint: '/containers/events', auth_type: 'NONE' }
test('relative path rejects external URLs and query/fragment injection', () => {
  assert.equal(validateCapability(rest), '')
  for (const endpoint of ['https://evil.test/a', '//evil.test/a', '/containers/events?token=1', '/a#b', 'evil/containers/events', ''])
    assert.notEqual(validateCapability({ ...rest, endpoint }), '')
})
test('type-specific fields reject mismatched auth, missing templates and SQL', () => {
  assert.notEqual(validateCapability({ ...rest, auth_type: 'SASL' }), '')
  const kafka = { ...rest, type: 'KAFKA_TOPIC', topic: 'port.container.event.v1', auth_type: 'SASL' }
  assert.equal(validateCapability(kafka), '')
  assert.notEqual(validateCapability({ ...kafka, auth_type: 'TOKEN' }), '')
  assert.notEqual(validateCapability({ ...kafka, topic: 'bad topic' }), '')
  assert.notEqual(validateCapability({ ...kafka, type: 'CDC_STREAM', source_table: '' }), '')
  for (const query_template_code of ['', 'select * from container_event', '1QUERY'])
    assert.notEqual(validateCapability({ ...rest, type: 'READONLY_QUERY', query_template_code }), '')
  assert.equal(validateCapability({ ...rest, type: 'READONLY_QUERY', query_template_code: 'QUERY_CONTAINER_EVENT' }), '')
})
test('PowerShell sample includes the exact configured address and capability header', () => {
  const command = invocationCommand({ request_url: 'https://provider.example:18443/containers/events', http_method: 'GET', header_name: 'X-Capability-Id', header_value: 'CAP_real' })
  assert.match(command, /https:\/\/provider.example:18443\/containers\/events\?limit=5/)
  assert.match(command, /'X-Capability-Id' = 'CAP_real'/)
  assert.match(invocationCommand({ request_url: 'https://example/a', http_method: 'GET', header_name: 'X-Test', header_value: "a'b" }), /'a''b'/)
})
