(function (win) {
  async function request(url, method, data, headers) {
    const response = await win.SmartWasteAuth.fetch(url, {
      method: method || 'GET', credentials: 'same-origin',
      headers: Object.assign({ 'Content-Type': 'application/json' }, headers || {}),
      body: data === undefined ? undefined : JSON.stringify(data)
    })
    if (response.status === 401 && (win.location.pathname.includes('catalog') || win.location.pathname.includes('drivers'))) {
      win.location.assign('/backend/page/login/login.html')
      throw new Error('请先登录管理端')
    }
    const result = await response.json()
    if (!response.ok || String(result.code) !== '1') throw new Error(result.msg || '操作失败，请稍后重试')
    return result.data
  }
  function requestId() {
    if (win.crypto.randomUUID) return win.crypto.randomUUID()
    const bytes = new Uint8Array(16)
    win.crypto.getRandomValues(bytes)
    return Array.from(bytes, value => value.toString(16).padStart(2, '0')).join('')
  }
  win.WasteWorkflow = { request, requestId }
})(window)
