new Vue({
  el: '#app',
  data() { return { documents: [], status: null, busy: false, saving: false, visible: false, error: '', form: {},
    categories: [{ value: 'classification', label: '垃圾分类' }, { value: 'disposal', label: '投放规则' }, { value: 'device', label: '设备使用' }, { value: 'platform', label: '平台流程' }] } },
  created() { this.load() },
  methods: {
    categoryLabel(value) { const item = this.categories.find(c => c.value === value); return item ? item.label : value },
    async load() {
      this.busy = true; this.error = ''
      try {
        this.documents = await WasteWorkflow.request('/rag/admin/documents')
        this.status = await WasteWorkflow.request('/rag/admin/status')
      } catch (error) { this.error = error.message }
      finally { this.busy = false }
    },
    create() { this.form = { title: '', category: 'classification', source: '', content: '' }; this.visible = true },
    closeEditor(done) { if (!this.saving) done() },
    async edit(row) {
      this.busy = true
      try { this.form = await WasteWorkflow.request('/rag/admin/documents/' + row.id); this.visible = true }
      catch (error) { this.$message.error(error.message) }
      finally { this.busy = false }
    },
    async importFile(event) {
      const file = event.target.files[0]; event.target.value = ''
      if (!file) return
      if (!/\.(md|txt)$/i.test(file.name) || file.size > 400000) { this.$message.error('请选择 400KB 以内的 Markdown 或纯文本文件'); return }
      try {
        const content = await file.text()
        if (content.length > 100000) throw new Error('知识正文不能超过 10 万字')
        this.form.content = content
        if (!this.form.title) this.form.title = file.name.replace(/\.(md|txt)$/i, '').slice(0, 120)
        if (!this.form.source) this.form.source = file.name.slice(0, 300)
      } catch (error) { this.$message.error(error.message || '文件读取失败，请使用 UTF-8 文本文件') }
    },
    showWarnings(result) { (result.warnings || []).forEach(w => this.$message.warning(w)) },
    async save() {
      if (this.saving) return
      if (!this.form.title.trim() || !this.form.source.trim() || !this.form.content.trim()) { this.$message.error('请填写标题、来源和知识正文'); return }
      this.saving = true
      try {
        const body = { title: this.form.title, category: this.form.category, source: this.form.source, content: this.form.content }
        const result = await WasteWorkflow.request('/rag/admin/documents' + (this.form.id ? '/' + this.form.id : ''), this.form.id ? 'PUT' : 'POST', body)
        this.showWarnings(result); this.$message.success('知识已保存并建立索引'); this.visible = false; await this.load()
      } catch (error) { this.$message.error(error.message) }
      finally { this.saving = false }
    },
    async seed() {
      if (this.busy) return
      this.busy = true
      try {
        const result = await WasteWorkflow.request('/rag/admin/seed', 'POST')
        this.$message.success('已导入 ' + result.imported + ' 篇，跳过已有示例 ' + result.skipped + ' 篇'); await this.load()
      } catch (error) { this.$message.error(error.message) }
      finally { this.busy = false }
    },
    async reindex(row) {
      this.busy = true
      try { this.showWarnings(await WasteWorkflow.request('/rag/admin/documents/' + row.id + '/reindex', 'POST')); this.$message.success('索引已重建'); await this.load() }
      catch (error) { this.$message.error(error.message) }
      finally { this.busy = false }
    },
    async remove(row) {
      try {
        await this.$confirm('删除“' + row.title + '”后，客服将不再引用该知识，确认删除？', '删除知识')
        this.busy = true
        this.showWarnings(await WasteWorkflow.request('/rag/admin/documents/' + row.id, 'DELETE')); await this.load()
      } catch (error) { if (error && error.message) this.$message.error(error.message) }
      finally { this.busy = false }
    }
  }
})
