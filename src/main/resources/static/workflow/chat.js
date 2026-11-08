new Vue({
  el: '#app',
  data() { return {
    question: '', category: '', busy: false, error: '', messages: [],
    modes: { GENERATED: '知识问答', EXTRACTIVE: '知识原文', NO_EVIDENCE: '暂无依据' },
    categories: [{ value: '', label: '全部知识' }, { value: 'classification', label: '垃圾分类' }, { value: 'disposal', label: '投放规则' }, { value: 'device', label: '设备使用' }, { value: 'platform', label: '平台流程' }],
    examples: ['用过的纸巾属于什么垃圾？', '智能垃圾桶满了怎么办？', '上报满桶后怎么查看清运进度？']
  } },
  async created() {
    try { await WasteWorkflow.request('/userAndroid/me') }
    catch (error) { window.location.assign('/workflow/login.html?role=user') }
  },
  methods: {
    useExample(value) { this.question = value; this.send() },
    clear() { if (!this.busy) { this.messages = []; this.error = '' } },
    async send() {
      const question = this.question.trim()
      if (this.busy || !question) return
      const history = this.messages.filter(m => !m.failed && (m.role === 'user' || (m.result && m.result.mode !== 'NO_EVIDENCE')))
        .slice(-8).map(m => ({ role: m.role, content: m.content.slice(0, 2000) }))
      const message = { role: 'user', content: question, failed: false }
      this.messages.push(message); this.question = ''; this.busy = true; this.error = ''
      try {
        const result = await WasteWorkflow.request('/rag/chat', 'POST', { question, category: this.category, history })
        this.messages.push({ role: 'assistant', content: result.answer, result })
      } catch (error) {
        message.failed = true; this.error = error.message; this.question = question
      } finally { this.busy = false }
    }
  }
})
