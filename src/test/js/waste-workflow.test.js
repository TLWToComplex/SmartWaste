const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const folder = path.resolve(__dirname, '../../main/resources/static/workflow')

function page(file, request) {
  let options
  const notices = [], redirects = []
  const context = { Vue: function(config) { options = config }, URLSearchParams,
    window: { location: { search: '?role=user', assign: url => redirects.push(url) } },
    WasteWorkflow: { request, requestId: () => 'stable-request-id' } }
  vm.runInNewContext(fs.readFileSync(path.join(folder, file), 'utf8'), context)
  const instance = options.data()
  instance.$set = (target, key, value) => { target[key] = value }
  instance.$message = { success: value => notices.push(value), error: value => notices.push(value) }
  instance.$confirm = async () => true
  for (const [name, method] of Object.entries(options.methods)) instance[name] = method.bind(instance)
  return { instance, options, notices, redirects }
}

test('user report retries reuse the request ID and successful reports clear it', async () => {
  const sent = []
  let failed = true
  const { instance } = page('user.js', async (url, method, body) => {
    if (url.includes('/full-reports')) {
      sent.push(body.requestId)
      if (failed) throw new Error('network unavailable')
      return { orderNumber: 'SW123' }
    }
    return []
  })
  await instance.reportFull({ id: '100000000000000001' })
  assert.equal(instance.reportRequests['100000000000000001'], 'stable-request-id')
  failed = false
  await instance.reportFull({ id: '100000000000000001' })
  assert.deepEqual(sent, ['stable-request-id', 'stable-request-id'])
  assert.equal(instance.reportRequests['100000000000000001'], undefined)
  assert.equal(instance.submitting['100000000000000001'], false)
})

test('double-clicking a full-bin report only submits once while pending', async () => {
  let resolve, calls = 0
  const { instance } = page('user.js', (url) => {
    if (url.includes('/full-reports')) { calls++; return new Promise(done => { resolve = done }) }
    return Promise.resolve([])
  })
  const first = instance.reportFull({ id: '1' })
  await instance.reportFull({ id: '1' })
  assert.equal(calls, 1)
  resolve({ orderNumber: 'SW1' }); await first
})

test('driver accepts by POST without truncating large order IDs and opens own tasks', async () => {
  const calls = []
  const { instance } = page('driver.js', async (url, method) => { calls.push({ url, method }); return [] })
  await instance.act({ id: '1999999999999999999' }, 'accept')
  assert.equal(calls[0].url, '/orderAndroid/1999999999999999999/accept')
  assert.equal(calls[0].method, 'POST')
  assert.equal(instance.scope, 'mine')
  assert.equal(calls[1].url, '/orderAndroid/map2?scope=mine')
})

test('old driver-list responses cannot overwrite a newer selected scope', async () => {
  const resolves = []
  const { instance } = page('driver.js', () => new Promise(resolve => resolves.push(resolve)))
  const first = instance.load()
  instance.scope = 'mine'
  const second = instance.load()
  resolves[1]([{ id: 'mine' }]); await second
  resolves[0]([{ id: 'pending' }]); await first
  assert.equal(instance.orders[0].id, 'mine')
})

test('device simulator sends its own credentials and a reusable request ID', async () => {
  const calls = []
  const { instance } = page('catalog.js', async (url, method, body, headers) => {
    calls.push({ url, method, body, headers })
    return url.startsWith('/device/') ? { orderNumber: 'SW1' } : []
  })
  instance.openSimulator({ id: '123', code: 'B001', deviceId: 'DEV-B001' })
  instance.simulation.token = 'test-token'
  await instance.simulate()
  assert.equal(calls[0].url, '/device/bins/123/full-reports')
  assert.equal(calls[0].headers['X-Device-Id'], 'DEV-B001')
  assert.equal(calls[0].headers['X-Device-Token'], 'test-token')
  assert.equal(calls[0].body.requestId, 'stable-request-id')
  assert.equal(instance.simulating, false)
})

test('a failed login does not navigate into the workspace', async () => {
  const result = page('login.js', async () => { throw new Error('账号或密码错误') })
  await result.instance.submit()
  assert.equal(result.redirects.length, 0)
  assert.match(result.notices[0], /密码/)
  assert.equal(result.instance.busy, false)
})

test('shared request helper sends same-origin credentials and rejects business errors', async () => {
  const calls = []
  const win = { location: { pathname: '/workflow/user.html' }, crypto: { randomUUID: () => 'id' },
    fetch: async (url, options) => { calls.push(options); return { ok: true, status: 200, json: async () => ({ code: 0, msg: '桶已停用' }) } } }
  vm.runInNewContext(fs.readFileSync(path.join(folder, 'api.js'), 'utf8'), { window: win })
  await assert.rejects(win.WasteWorkflow.request('/waste/bins/1/full-reports', 'POST', { requestId: 'id' }), /桶已停用/)
  assert.equal(calls[0].credentials, 'same-origin')
  assert.equal(calls[0].method, 'POST')
})
