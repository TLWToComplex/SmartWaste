new Vue({
  el: '#app',
  data() { return { profile: {}, orders: [], scope: 'pending', busy: false, error: '', acting: {}, loadRequestId: 0,
    tracking: null, trackingOrderId: '', trackingStarting: false, trackingMessage: '' } },
  async created() {
    try { this.profile = await WasteWorkflow.request('/driver/me'); await this.load() }
    catch (error) { window.location.assign('/workflow/login.html?role=driver') }
  },
  beforeDestroy() { this.stopTracking() },
  methods: {
    async load() {
      const requestId = ++this.loadRequestId
      this.busy = true; this.error = ''
      try {
        const orders = await WasteWorkflow.request('/orderAndroid/map2?scope=' + this.scope)
        if (requestId === this.loadRequestId) {
          this.orders = orders
          if (this.scope === 'mine' && this.trackingOrderId && !orders.some(o => String(o.id) === this.trackingOrderId && o.status === 2)) this.stopTracking()
        }
      }
      catch (error) { if (requestId === this.loadRequestId) this.error = error.message }
      finally { if (requestId === this.loadRequestId) this.busy = false }
    },
    async act(order, action) {
      if (this.acting[order.id]) return
      this.$set(this.acting, order.id, true)
      try {
        await this.$confirm(action === 'accept' ? '确认接取该清运任务？' : '请确认垃圾已清运完成，完成后将恢复桶状态。', '提示')
        await WasteWorkflow.request('/orderAndroid/' + order.id + '/' + action, 'POST')
        if (action === 'complete' && String(order.id) === this.trackingOrderId) this.stopTracking()
        this.$message.success(action === 'accept' ? '接单成功' : '清运已完成')
        this.scope = 'mine'; await this.load()
      } catch (error) { if (error && error.message) this.$message.error(error.message) }
      finally { this.$set(this.acting, order.id, false) }
    },
    async startTracking(order) {
      if (this.trackingStarting) return
      this.trackingStarting = true
      try {
        await this.$confirm('开启后每10秒上报当前位置，供管理端监管本次清运；请保持页面打开，完成任务后会停止上报。', '开启清运定位')
        this.stopTracking()
        this.trackingOrderId = String(order.id)
        this.tracking = await WasteTracking.start(order.id, { onUpdate: message => { this.trackingMessage = message }, onError: message => { this.trackingMessage = message } })
        this.trackingMessage = '正在获取当前位置…'
      } catch (error) { this.stopTracking(); if (error && error.message) this.$message.error(error.message) }
      finally { this.trackingStarting = false }
    },
    stopTracking() { if (this.tracking) this.tracking.stop(); this.tracking = null; this.trackingOrderId = ''; this.trackingMessage = '' },
    async logout() { await SmartWasteAuth.logout('driver'); this.stopTracking(); window.location.assign('/workflow/login.html?role=driver') }
  }
})
