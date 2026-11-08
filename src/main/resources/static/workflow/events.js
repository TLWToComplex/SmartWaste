new Vue({
  el: '#app', data() { return { events: [], total: 0, page: 1, busy: false, error: '', retrying: {} } },
  created() { this.load() },
  methods: {
    stateLabel(state) { return ({ NEW: '待发送', SENDING: '等待发送确认', SENT: '等待消费', FAILED: '处理失败' })[state] || state },
    changePage(page) { this.page = page; this.load() },
    async load() { this.busy = true; this.error = ''; try { const data = await WasteWorkflow.request('/waste/order-events?page=' + this.page + '&pageSize=10'); this.events = data.records; this.total = data.total }
      catch (error) { this.error = error.message } finally { this.busy = false } },
    async retry(event) {
      if (this.retrying[event.id]) return
      this.$set(this.retrying, event.id, true)
      try { await WasteWorkflow.request('/waste/order-events/' + event.id + '/retry', 'POST'); this.$message.success('已安排重新处理'); await this.load() }
      catch (error) { this.$message.error(error.message) } finally { this.$set(this.retrying, event.id, false) }
    }
  }
})
