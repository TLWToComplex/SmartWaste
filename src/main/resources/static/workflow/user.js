new Vue({
  el: '#app',
  data() { return { profile: {}, bins: [], orders: [], reports: [], busy: false, error: '', submitting: {}, reportRequests: {}, pollTimer: null, loadRequestId: 0, disposed: false } },
  async created() {
    try { this.profile = await WasteWorkflow.request('/userAndroid/me'); await this.load() }
    catch (error) { window.location.assign('/workflow/login.html?role=user') }
  },
  beforeDestroy() { this.disposed = true; window.clearTimeout(this.pollTimer) },
  methods: {
    async load() {
      const requestId = ++this.loadRequestId
      window.clearTimeout(this.pollTimer)
      this.busy = true; this.error = ''
      try {
        const results = await Promise.all([WasteWorkflow.request('/waste/bins'), WasteWorkflow.request('/orderAndroid/map1'), WasteWorkflow.request('/waste/reports/mine')])
        if (requestId !== this.loadRequestId) return
        this.bins = results[0]; this.orders = results[1]; this.reports = results[2]
        if (this.reports.some(r => r.processingState !== 'DONE' && r.processingState !== 'FAILED')) this.pollTimer = window.setTimeout(() => { if (!this.disposed) this.load() }, 3000)
      } catch (error) { if (requestId === this.loadRequestId) this.error = error.message }
      finally { if (requestId === this.loadRequestId) this.busy = false }
    },
    async reportFull(bin) {
      if (this.submitting[bin.id]) return
      this.$set(this.submitting, bin.id, true)
      if (!this.reportRequests[bin.id]) this.reportRequests[bin.id] = WasteWorkflow.requestId()
      try {
        const result = await WasteWorkflow.request('/waste/bins/' + bin.id + '/full-reports', 'POST', { requestId: this.reportRequests[bin.id] })
        delete this.reportRequests[bin.id]
        this.$message.success(result.message || '上报已受理，正在生成清运订单')
        await this.load()
      } catch (error) { this.$message.error(error.message) }
      finally { this.$set(this.submitting, bin.id, false) }
    },
    async cancel(order) {
      try {
        await this.$confirm('取消订单不会将垃圾桶标记为空桶，确认取消？', '提示')
        await WasteWorkflow.request('/orderAndroid/' + order.id + '/cancel', 'POST')
        this.$message.success('订单已取消'); await this.load()
      } catch (error) { if (error && error.message) this.$message.error(error.message) }
    },
    async logout() { await SmartWasteAuth.logout('user'); this.disposed = true; window.clearTimeout(this.pollTimer); window.location.assign('/workflow/login.html?role=user') }
  }
})
