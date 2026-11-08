const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')

const backend = path.resolve(__dirname, '../../main/resources/static/backend')
const html = fs.readFileSync(path.join(backend, 'page/order/list.html'), 'utf8')
const script = [...html.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/g)]
  .map(match => match[1]).find(source => source.includes('new Vue('))

function createPage(getReminders) {
  let options
  vm.runInNewContext(script, { Vue: function (config) { options = config }, getOverdueOrderReminders: getReminders })
  const page = options.data()
  for (const [name, method] of Object.entries(options.methods)) page[name] = method.bind(page)
  return page
}

test('reminder API passes pagination to the dedicated endpoint', async () => {
  let request
  const context = { $axios: async config => { request = config } }
  vm.runInNewContext(fs.readFileSync(path.join(backend, 'api/order.js'), 'utf8'), context)
  await vm.runInNewContext('getOverdueOrderReminders({ page: 2, pageSize: 5 })', context)
  assert.equal(request.url, '/order/overdue-reminders')
  assert.equal(request.params.page, 2)
  assert.equal(request.params.pageSize, 5)
})

test('a zero total clears previously displayed overdue reminders', async () => {
  const page = createPage(async () => ({ code: 1, data: { total: 0, records: [] } }))
  page.reminders = { total: 1, records: [{ orderId: '1' }] }
  await page.loadReminders()
  assert.equal(page.reminders.total, 0)
  assert.equal(page.reminders.records.length, 0)
  assert.equal(page.remindersError, '')
})

test('failed refresh preserves the last result with a visible stale-data error', async () => {
  const page = createPage(async () => { throw new Error('offline') })
  const last = { total: 3, records: [] }
  page.reminders = last
  await page.loadReminders()
  assert.equal(page.reminders, last)
  assert.match(page.remindersError, /不是最新结果/)
  assert.equal(page.remindersLoading, false)
})

test('a shrinking list moves an empty page back to the last available page', async () => {
  const pages = []
  const page = createPage(async params => {
    pages.push(params.page)
    return { code: 1, data: { total: 1, records: params.page === 1 ? [{ orderId: '1' }] : [] } }
  })
  page.reminderPage = 2
  await page.loadReminders()
  assert.deepEqual(pages, [2, 1])
  assert.equal(page.reminderPage, 1)
  assert.equal(page.reminders.records.length, 1)
  assert.equal(page.remindersLoading, false)
})

test('a stale HTTP response cannot restore reminders that were already cleared', async () => {
  const resolves = []
  const page = createPage(() => new Promise(resolve => resolves.push(resolve)))
  const first = page.loadReminders()
  const second = page.loadReminders()
  resolves[1]({ code: 1, data: { total: 0, records: [] } })
  await second
  resolves[0]({ code: 1, data: { total: 1, records: [{ orderId: '1' }] } })
  await first
  assert.equal(page.reminders.total, 0)
})

function socketEnvironment(callbacks = {}) {
  const sockets = [], timers = new Map(), intervals = new Map()
  let seq = 0, now = 0
  const states = []
  let refreshes = 0, failures = 0
  class FakeSocket {
    constructor(url) { this.url = url; this.readyState = 0; this.sent = []; sockets.push(this) }
    send(message) { this.sent.push(message) }
    close() { this.readyState = 3; if (this.onclose) this.onclose({ code: 1000 }) }
    open() { this.readyState = 1; this.onopen() }
    receive(value) { this.onmessage({ data: value }) }
  }
  const win = {
    WebSocket: FakeSocket,
    location: { protocol: 'https:', host: 'example.test:8443' },
    setTimeout: (fn, delay) => { timers.set(++seq, { fn, delay }); return seq },
    clearTimeout: id => timers.delete(id),
    setInterval: (fn, delay) => { intervals.set(++seq, { fn, delay }); return seq },
    clearInterval: id => intervals.delete(id)
  }
  vm.runInNewContext(fs.readFileSync(path.join(backend, 'js/order-reminder-socket.js'), 'utf8'),
    { window: win, Date: { now: () => now } })
  const connection = win.createOrderReminderSocket({
    onState: state => states.push(state), onRefresh: () => refreshes++, onScanError: () => failures++, ...callbacks
  })
  return { win, sockets, timers, intervals, states, connection,
    refreshes: () => refreshes, failures: () => failures, advance: value => { now = value } }
}

test('uses same-origin wss and refreshes on connection and server notifications', () => {
  const env = socketEnvironment()
  const socket = env.sockets[0]
  assert.equal(socket.url, 'wss://example.test:8443/ws/admin/order-reminders')
  socket.open()
  assert.equal(env.refreshes(), 1)
  socket.receive('{"type":"order-reminders.updated","total":0}')
  assert.equal(env.refreshes(), 2)
  socket.receive('{"type":"pong"}')
  socket.receive('invalid json')
  assert.equal(env.refreshes(), 2)
  socket.receive('{"type":"order-reminders.error"}')
  assert.equal(env.failures(), 1)
  env.connection.stop()
})

test('reconnects after disconnect and reloads reminders after reconnection', () => {
  const env = socketEnvironment()
  env.sockets[0].open()
  env.sockets[0].onclose({ code: 1006 })
  assert.equal(env.timers.size, 1)
  const [id, timer] = [...env.timers][0]
  assert.equal(timer.delay, 1000)
  env.timers.delete(id)
  timer.fn()
  env.sockets[1].open()
  assert.equal(env.refreshes(), 2)
  env.connection.stop()
  assert.equal(env.timers.size, 0)
  assert.equal(env.intervals.size, 0)
})

test('an expired login stops reconnecting', () => {
  const env = socketEnvironment()
  env.sockets[0].open()
  env.sockets[0].onclose({ code: 1008 })
  assert.equal(env.states.at(-1), 'unauthorized')
  assert.equal(env.timers.size, 0)
  assert.equal(env.intervals.size, 0)
})

test('heartbeat detects a silent connection and schedules reconnection', () => {
  const env = socketEnvironment()
  env.sockets[0].open()
  const heartbeat = [...env.intervals.values()][0].fn
  heartbeat()
  assert.deepEqual(env.sockets[0].sent, ['ping'])
  env.advance(76000)
  heartbeat()
  assert.equal(env.timers.size, 1)
  env.connection.stop()
})
