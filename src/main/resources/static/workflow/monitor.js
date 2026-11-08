new Vue({
  el: '#app',
  data() { return { vehicles: [], error: '', mapError: '', connection: 'connecting',
    socket: null, timer: null, selected: null, routeInfo: '', clock: Date.now(), loadVersion: 0, routeVersion: 0, disposed: false } },
  computed: { connectionLabel() { return ({ connected: '实时连接已建立', connecting: '正在连接', reconnecting: '正在重连', unsupported: '浏览器不支持实时连接', unauthorized: '请重新登录' })[this.connection] || this.connection } },
  created() { this.map = null; this.markers = {}; this.overlays = [] },
  async mounted() {
    await this.load()
    try { const AMap = await WasteMap.sdk(); if (!this.disposed) { this.map = new AMap.Map('vehicle-map', { zoom: 12, center: [108.94, 34.26] }); this.drawMarkers() } }
    catch (error) { this.mapError = error.message }
    if (this.disposed) return
    this.socket = SmartWasteAuth.openSocket('/ws/admin/vehicles', {
      onState: state => { this.connection = state }, onReady: () => this.load(),
      onMessage: message => { if (message.type === 'vehicle.updated') this.upsert(message.data); if (message.type === 'vehicles.refresh') this.load() }
    })
    this.timer = window.setInterval(() => { this.clock = Date.now(); this.load() }, 30000)
  },
  beforeDestroy() { this.disposed = true; window.clearInterval(this.timer); if (this.socket) this.socket.stop(); if (this.map) this.map.destroy() },
  methods: {
    fresh(vehicle) { return this.clock - Number(vehicle.observedAt) < 60000 && vehicle.orderStatus === 2 },
    async load() {
      const version = ++this.loadVersion
      try {
        const rows = await WasteWorkflow.request('/transport/vehicles')
        if (this.disposed || version !== this.loadVersion) return
        // HTTP 快照不能覆盖稍晚到达的 WebSocket 定位。
        this.vehicles = rows.map(row => { const current = this.vehicles.find(v => String(v.driverId) === String(row.driverId));
          return current && Number(current.observedAt) > Number(row.observedAt) ? Object.assign({}, current, { orderStatus: row.orderStatus }) : row })
        this.error = ''; this.clock = Date.now(); this.drawMarkers()
        if (this.selected) this.selected = this.vehicles.find(v => String(v.driverId) === String(this.selected.driverId)) || null
      } catch (error) { if (version === this.loadVersion) this.error = error.message }
    },
    upsert(vehicle) {
      if (!vehicle || !vehicle.driverId) return
      const index = this.vehicles.findIndex(v => String(v.driverId) === String(vehicle.driverId))
      if (index >= 0 && Number(this.vehicles[index].observedAt) >= Number(vehicle.observedAt)) return
      if (index >= 0) this.$set(this.vehicles, index, vehicle); else this.vehicles.push(vehicle)
      this.clock = Date.now(); this.drawMarkers()
      if (this.selected && String(this.selected.driverId) === String(vehicle.driverId)) {
        if (String(this.selected.orderId) !== String(vehicle.orderId)) this.select(vehicle)
        else this.selected = vehicle
      }
    },
    drawMarkers() {
      if (!this.map) return
      for (const row of this.vehicles) {
        const key = String(row.driverId), position = [Number(row.longitude), Number(row.latitude)]
        let marker = this.markers[key]
        if (!marker) {
          const label = document.createElement('span'); label.className = 'vehicle-label'; label.textContent = row.carNumber || '未登记车牌'
          marker = new AMap.Marker({ position, title: row.carNumber, label: { content: label, direction: 'top' } })
          marker.on('click', () => { const current = this.vehicles.find(v => String(v.driverId) === key); if (current) this.select(current) })
          this.markers[key] = marker; this.map.add(marker)
        } else marker.setPosition(position)
        marker.setOpacity(this.fresh(row) ? 1 : 0.45)
      }
    },
    async select(vehicle) {
      this.selected = vehicle; this.routeInfo = '正在加载路线…'
      const version = ++this.routeVersion
      if (this.map) { this.map.remove(this.overlays); this.overlays = []; this.map.setCenter([Number(vehicle.longitude), Number(vehicle.latitude)]) }
      try {
        const route = await WasteWorkflow.request('/transport/orders/' + vehicle.orderId + '/route')
        if (version !== this.routeVersion || this.disposed) return
        this.routeInfo = '桶站路线 ' + (Number(route.distanceMeters) / 1000).toFixed(2) + ' 公里，预计 ' + Math.ceil(Number(route.durationSeconds) / 60) + ' 分钟'
        if (this.map) this.overlays = WasteMap.drawRoute(this.map, route)
      } catch (error) { if (version === this.routeVersion) this.routeInfo = error.message }
    }
  }
})
