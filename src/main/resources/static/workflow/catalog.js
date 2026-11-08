new Vue({
  el: '#app',
  data() { return { bins: [], stations: [], tab: 'bins', busy: false, error: '', editing: false, saving: false, form: {},
    showToken: false, token: '', simulating: false, simulation: {}, reporting: false } },
  created() { this.load() },
  methods: {
    async load() {
      this.busy = true; this.error = ''
      try {
        const results = await Promise.all([WasteWorkflow.request('/waste/bins'), WasteWorkflow.request('/waste/stations')])
        this.bins = results[0]; this.stations = results[1]
      } catch (error) { this.error = error.message }
      finally { this.busy = false }
    },
    stationName(id) { const station = this.stations.find(s => String(s.id) === String(id)); return station ? station.name : '—' },
    edit(row) {
      this.form = row ? Object.assign({}, row) : { name: '', address: '', longitude: '', latitude: '', status: 1, code: '', wasteType: '', stationId: '', deviceId: '' }
      this.editing = true
    },
    async save() {
      if (this.saving) return
      this.saving = true
      try {
        const url = '/waste/' + this.tab + (this.form.id ? '/' + this.form.id : '')
        const result = await WasteWorkflow.request(url, this.form.id ? 'PUT' : 'POST', this.form)
        this.editing = false
        if (result.deviceToken) { this.token = result.deviceToken; this.showToken = true }
        this.$message.success('保存成功'); await this.load()
      } catch (error) { this.$message.error(error.message) }
      finally { this.saving = false }
    },
    async resetToken(bin) {
      try {
        await this.$confirm('重置后原设备密钥立即失效，确认继续？', '提示')
        const result = await WasteWorkflow.request('/waste/bins/' + bin.id + '/device-token', 'POST')
        this.token = result.deviceToken; this.showToken = true
      } catch (error) { if (error && error.message) this.$message.error(error.message) }
    },
    openSimulator(bin) { this.simulation = { id: bin.id, code: bin.code, deviceId: bin.deviceId, token: '', requestId: WasteWorkflow.requestId() }; this.simulating = true },
    async simulate() {
      if (this.reporting) return
      this.reporting = true
      try {
        const result = await WasteWorkflow.request('/device/bins/' + this.simulation.id + '/full-reports', 'POST',
          { requestId: this.simulation.requestId }, { 'X-Device-Id': this.simulation.deviceId, 'X-Device-Token': this.simulation.token })
        this.$message.success(result.message || '设备上报已受理，正在生成清运订单')
        this.simulating = false; await this.load()
      } catch (error) { this.$message.error(error.message) }
      finally { this.reporting = false }
    }
  }
})
