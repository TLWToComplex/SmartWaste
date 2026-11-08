new Vue({
  el: '#sort',
  data() { return {
    formInline: { name: '', sortId: '' },
    classifyForm: { imageUrl: '', name: '', sort: '', rate: '', uncertain: false, message: '', candidates: [] },
    tableData: [], currentPage: 1, totalCount: 0, classifyDialogVisible: false,
    identifying: false, disposed: false, uploadController: null, previewUrl: ''
  } },
  mounted() { this.selectByPage() },
  beforeDestroy() {
    this.disposed = true
    if (this.uploadController) this.uploadController.abort()
    this.clearPreview()
  },
  methods: {
    async selectByPage() {
      try {
        const query = new URLSearchParams({ page: this.currentPage, sortId: this.formInline.sortId, name: this.formInline.name })
        const page = await WasteWorkflow.request('/classification?' + query)
        if (!this.disposed) { this.tableData = page.records || []; this.totalCount = page.total }
      } catch (error) { if (!this.disposed) this.$message.error(error.message) }
    },
    beforeUpload(file) {
      if (this.identifying) return false
      if (!['image/jpeg', 'image/png', 'image/webp'].includes(file.type)) {
        this.$message.error('请选择 JPEG、PNG 或 WebP 图片'); return false
      }
      if (!file.size || file.size > 5 * 1024 * 1024) {
        this.$message.error('请选择 5MB 以内的图片'); return false
      }
      this.identifying = true
      return true
    },
    async uploadPhoto(options) {
      const controller = new AbortController()
      this.uploadController = controller
      const timer = window.setTimeout(() => controller.abort(), 30000)
      const form = new FormData(); form.append('file', options.file)
      try {
        const response = await SmartWasteAuth.fetch('/classification', { method: 'POST', body: form, signal: controller.signal }, 'user')
        if (response.status === 413) throw new Error('图片超过上传限制，请压缩后重试')
        const result = await response.json()
        if (!response.ok || String(result.code) !== '1' || !result.data) throw new Error(result.msg || '识别失败，请稍后重试')
        // Element UI 会处理返回 Promise 的成功/失败回调，避免手动回调造成重复处理。
        return result
      } catch (error) {
        if (error.name === 'AbortError') throw new Error('识别请求超时，请稍后重试')
        throw error
      } finally {
        window.clearTimeout(timer)
        this.uploadController = null
        this.identifying = false
      }
    },
    classifySuccess(result, file) {
      if (this.disposed) return
      this.clearPreview()
      this.previewUrl = URL.createObjectURL(file.raw)
      this.classifyForm = Object.assign({ candidates: [], message: '', uncertain: false }, result.data, { imageUrl: this.previewUrl })
      this.classifyDialogVisible = true
    },
    classifyFail(error) { if (!this.disposed) this.$message.error(error.message || '图片识别失败，请稍后重试') },
    clearPreview() {
      if (this.previewUrl) URL.revokeObjectURL(this.previewUrl)
      this.previewUrl = ''; this.classifyForm.imageUrl = ''
    },
    percent(value) { return (Number(value) * 100).toFixed(2) + '%' },
    query() { this.currentPage = 1; this.selectByPage() },
    handleCurrentChange(value) { this.currentPage = value; this.selectByPage() }
  }
})
