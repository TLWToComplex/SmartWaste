const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')

const backend = path.resolve(__dirname, '../../main/resources/static/backend')
const html = fs.readFileSync(path.join(backend, 'page/order/list.html'), 'utf8')
const script = [...html.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/g)]
  .map(match => match[1]).find(source => source.includes('new Vue('))

function createPage(getStatistics) {
  let options
  const context = {
    Vue: function (config) { options = config },
    getOrderStatistics: getStatistics,
    getOverdueOrderReminders: async () => ({ code: 1, data: { records: [], total: 0 } }),
    getOrderDetailPage: async () => ({ code: 1, data: { records: [], total: 0 } })
  }
  vm.runInNewContext(script, context)
  const page = options.data()
  page.$message = { error: message => assert.fail(message) }
  for (const [name, method] of Object.entries(options.methods)) page[name] = method.bind(page)
  return page
}

test('statistics API uses its own unfiltered endpoint', async () => {
  let request
  const context = { $axios: async config => { request = config; return { code: 1 } } }
  vm.runInNewContext(fs.readFileSync(path.join(backend, 'api/order.js'), 'utf8'), context)
  await vm.runInNewContext('getOrderStatistics()', context)
  assert.equal(request.url, '/order/statistics')
  assert.equal(request.method, 'get')
  assert.equal(request.params, undefined)
})

test('zero counts are displayed as valid data', async () => {
  const data = { pendingCount: 0, inTransitCount: 0, completedCount: 0, todayCount: 0, statisticDate: '2026-11-08' }
  const page = createPage(async () => ({ code: 1, data }))
  await page.loadStatistics()
  assert.equal(page.statistics, data)
  assert.equal(page.statisticsError, '')
  assert.equal(page.statisticsLoading, false)
})

test('failed requests clear stale counts and can be retried', async () => {
  let fail = true
  const page = createPage(async () => {
    if (fail) throw new Error('offline')
    return { code: 1, data: { todayCount: 2 } }
  })
  page.statistics = { todayCount: 99 }
  await page.loadStatistics()
  assert.equal(page.statistics, null)
  assert.match(page.statisticsError, /刷新统计/)
  assert.equal(page.statisticsLoading, false)
  fail = false
  await page.loadStatistics()
  assert.equal(page.statistics.todayCount, 2)
  assert.equal(page.statisticsError, '')
})

test('business errors are shown without misleading zero counts', async () => {
  const page = createPage(async () => ({ code: 0, msg: 'database unavailable' }))
  await page.loadStatistics()
  assert.equal(page.statistics, null)
  assert.ok(page.statisticsError)
})

test('an older response cannot overwrite a newer refresh', async () => {
  const pending = []
  const page = createPage(() => new Promise(resolve => pending.push(resolve)))
  const oldRequest = page.loadStatistics()
  const newRequest = page.loadStatistics()
  pending[1]({ code: 1, data: { todayCount: 2 } })
  await newRequest
  pending[0]({ code: 1, data: { todayCount: 1 } })
  await oldRequest
  assert.equal(page.statistics.todayCount, 2)
  assert.equal(page.statisticsLoading, false)
})

test('list reload refreshes statistics without forwarding list filters', async () => {
  let calls = 0
  const page = createPage(async () => { calls++; return { code: 1, data: { todayCount: 1 } } })
  page.page = 5
  page.input2 = '3'
  page.handleQuery()
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(page.page, 1)
  assert.equal(calls, 1)
  assert.equal(page.statistics.todayCount, 1)
})
