new Vue({
  el: '#app',
  data() {
    const query = new URLSearchParams(window.location.search)
    return { role: query.get('role') === 'driver' ? 'driver' : 'user', registering: query.get('register') === '1',
      busy: false, form: { username: '', password: '', name: '', phone: '', carNumber: '' } }
  },
  methods: {
    async submit() {
      if (this.busy) return
      this.busy = true
      try {
        const prefix = this.role === 'driver' ? '/driver' : '/userAndroid'
        const data = await WasteWorkflow.request(prefix + (this.registering ? '/register' : '/login'), 'POST', this.form)
        SmartWasteAuth.store(this.role, data)
        window.location.assign('/workflow/' + this.role + '.html')
      } catch (error) { this.$message.error(error.message) }
      finally { this.busy = false }
    }
  }
})
