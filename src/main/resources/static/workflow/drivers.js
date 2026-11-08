new Vue({
  el: '#app',
  data() { return { drivers: [], page: 1, total: 0, busy: false, error: '', editing: false, saving: false, form: {} } },
  created() { this.load() },
  methods: {
    async load() {
      this.busy = true; this.error = ''
      try { const data = await WasteWorkflow.request('/driver/page?page=' + this.page + '&pageSize=10'); this.drivers = data.records; this.total = data.total }
      catch (error) { this.error = error.message }
      finally { this.busy = false }
    },
    changePage(page) { this.page = page; this.load() },
    edit(row) { this.form = row ? Object.assign({}, row, { password: '' }) : { username: '', name: '', phone: '', carNumber: '', password: '', status: 1 }; this.editing = true },
    async save() {
      if (this.saving) return
      this.saving = true
      try { await WasteWorkflow.request('/driver', this.form.id ? 'PUT' : 'POST', this.form); this.editing = false; await this.load(); this.$message.success('保存成功') }
      catch (error) { this.$message.error(error.message) }
      finally { this.saving = false }
    }
  }
})
