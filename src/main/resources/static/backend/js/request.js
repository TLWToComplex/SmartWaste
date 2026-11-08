(function (win) {
  // 复用三端统一认证；遇到访问令牌过期时刷新一次，再重试原请求。
  async function service(config) {
    const url = new URL(config.url, win.location.origin)
    if (config.params) Object.keys(config.params).forEach(key => {
      const value = config.params[key]
      if (value !== null && value !== undefined && value !== '') url.searchParams.set(key, value)
    })
    const init = { method: (config.method || 'GET').toUpperCase(), headers: Object.assign({ 'Content-Type': 'application/json;charset=utf-8' }, config.headers || {}) }
    if (config.data !== undefined) init.body = JSON.stringify(config.data)
    try {
      const response = await win.SmartWasteAuth.fetch(url.pathname + url.search, init, 'admin')
      const result = await response.json()
      if (!response.ok) throw new Error(result.msg || '请求失败')
      return result
    } catch (error) {
      if (win.ELEMENT && win.ELEMENT.Message) win.ELEMENT.Message.error(error.message || '接口请求失败')
      throw error
    }
  }
  win.$axios = service
})(window)
