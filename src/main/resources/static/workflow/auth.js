(function (win) {
  const pending = {}
  function role() {
    const path = win.location.pathname
    if (path.startsWith('/backend/') || /\/workflow\/(catalog|drivers|events|monitor)\.html/.test(path)) return 'admin'
    if (path.endsWith('/route.html')) return new URLSearchParams(win.location.search).get('role') === 'admin' ? 'admin' : 'driver'
    if (path.includes('/driver') || new URLSearchParams(win.location.search).get('role') === 'driver') return 'driver'
    return 'user'
  }
  const key = r => 'smartwaste.access.' + r
  function token(r) { return win.sessionStorage.getItem(key(r)) }
  function store(r, data) {
    if (!data || data.role !== r || !data.accessToken) throw new Error('登录返回的身份信息无效')
    win.sessionStorage.setItem(key(r), data.accessToken)
  }
  function login(r) { win.top.location.assign(r === 'admin' ? '/backend/page/login/login.html' : '/workflow/login.html?role=' + r) }
  async function refresh(r, failedToken) {
    if (pending[r]) return pending[r]
    const execute = async () => {
      if (token(r) && token(r) !== failedToken) return
      const response = await win.fetch('/auth/' + r + '/refresh', { method: 'POST', credentials: 'same-origin' })
      const result = await response.json()
      if (!response.ok || String(result.code) !== '1') throw new Error(result.msg || '登录已失效')
      store(r, result.data)
    }
    // 同一浏览器的多个管理页面共用刷新 Cookie，串行刷新避免轮换凭据互相覆盖。
    pending[r] = (win.navigator.locks ? win.navigator.locks.request('smartwaste.refresh.' + r, execute) : execute())
      .finally(() => { delete pending[r] })
    return pending[r]
  }
  async function authorizedFetch(url, options, selectedRole) {
    const r = selectedRole || role(), init = Object.assign({}, options, { credentials: 'same-origin' })
    const headers = new Headers(init.headers || {})
    const publicEntry = /\/(login|register)$/.test(url)
    const old = token(r)
    if (!publicEntry && old) headers.set('Authorization', 'Bearer ' + old)
    init.headers = headers
    let response = await win.fetch(url, init)
    if (response.status === 401 && !publicEntry) {
      try {
        await refresh(r, old)
        headers.set('Authorization', 'Bearer ' + token(r))
        response = await win.fetch(url, init)
        if (response.status === 401) throw new Error('登录已失效，请重新登录')
      } catch (error) {
        win.sessionStorage.removeItem(key(r)); login(r); throw error
      }
    }
    return response
  }
  async function request(url, options, selectedRole) {
    const response = await authorizedFetch(url, options, selectedRole), result = await response.json()
    if (!response.ok || String(result.code) !== '1') throw new Error(result.msg || '请求失败')
    return result.data
  }
  async function logout(r) {
    await request('/auth/' + r + '/logout', { method: 'POST' }, r)
    win.sessionStorage.removeItem(key(r))
  }
  function openSocket(path, options) {
    let socket = null, timer = null, heartbeat = null, stopped = false, delay = 1000, last = 0
    const state = value => { if (options.onState) options.onState(value) }
    const retry = () => {
      if (stopped || timer !== null) return
      state('reconnecting'); timer = win.setTimeout(() => { timer = null; connect() }, delay); delay = Math.min(30000, delay * 2)
    }
    async function connect() {
      if (stopped) return
      if (!win.WebSocket) { state('unsupported'); return }
      state('connecting')
      try {
        const ticket = await request('/auth/admin/ws-ticket', { method: 'POST' }, 'admin')
        if (stopped) return
        const current = new win.WebSocket((win.location.protocol === 'https:' ? 'wss://' : 'ws://') + win.location.host + path + '?ticket=' + encodeURIComponent(ticket.ticket))
        socket = current
        current.onopen = () => {
          if (stopped || current !== socket) return
          delay = 1000; last = Date.now(); state('connected'); if (options.onReady) options.onReady()
          heartbeat = win.setInterval(() => { if (Date.now() - last > 75000) current.close(); else if (current.readyState === 1) current.send('ping') }, 25000)
        }
        current.onmessage = event => {
          if (current !== socket || stopped) return
          last = Date.now()
          try { const message = JSON.parse(event.data); if (options.onMessage) options.onMessage(message) } catch (error) { /* 忽略协议外数据 */ }
        }
        current.onclose = event => {
          if (current !== socket) return
          win.clearInterval(heartbeat); heartbeat = null; socket = null
          if (!stopped && event.code !== 1008) retry()
          else if (!stopped) state('unauthorized')
        }
        current.onerror = () => { if (!stopped) state('reconnecting') }
      } catch (error) { if (!stopped) retry() }
    }
    connect()
    return { stop() { stopped = true; win.clearTimeout(timer); win.clearInterval(heartbeat); if (socket) socket.close(); socket = null } }
  }
  win.SmartWasteAuth = { role, token, store, fetch: authorizedFetch, request, logout, openSocket }
})(window)
