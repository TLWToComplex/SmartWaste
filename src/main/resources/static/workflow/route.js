new Vue({
  el: '#app', data() { return { route: null, busy: false, error: '' } },
  created() { this.map = null; this.load() },
  beforeDestroy() { if (this.map) this.map.destroy() },
  methods: {
    navigation(coordinate, name) { return WasteMap.navigation(coordinate, name) },
    async load() {
      if (this.busy) return
      this.busy = true; this.error = ''
      try {
        const id = new URLSearchParams(window.location.search).get('id')
        if (!id || !/^\d+$/.test(id)) throw new Error('缺少有效订单编号')
        this.route = await WasteWorkflow.request('/transport/orders/' + id + '/route')
        const AMap = await WasteMap.sdk()
        if (!this.map) this.map = new AMap.Map('route-map', { zoom: 13 })
        this.map.clearMap(); WasteMap.drawRoute(this.map, this.route)
      } catch (error) { this.error = error.message }
      finally { this.busy = false }
    }
  }
})
