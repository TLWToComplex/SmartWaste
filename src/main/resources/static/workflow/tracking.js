(function (win) {
  win.WasteTracking = {
    async start(orderId, options) {
      if (!win.isSecureContext) throw new Error('定位需要 HTTPS 或 localhost，请使用安全地址打开司机端')
      const AMap = await WasteMap.sdk()
      const locator = new AMap.Geolocation({ enableHighAccuracy: true, timeout: 8000, convert: true })
      let stopped = false, busy = false
      async function tick() {
        if (stopped || busy) return
        busy = true
        try {
          const position = await new Promise((resolve, reject) => locator.getCurrentPosition((status, data) => {
            if (status === 'complete' && data.position) resolve(data)
            else reject(new Error('定位失败，请检查定位权限与网络'))
          }))
          if (stopped) return
          await WasteWorkflow.request('/transport/location', 'POST', { orderId, longitude: position.position.lng, latitude: position.position.lat,
            coordinateSystem: 'GCJ02', accuracyMeters: position.accuracy === undefined ? null : position.accuracy, observedAt: Date.now() })
          if (!stopped && options.onUpdate) options.onUpdate('位置已上报：' + new Date().toLocaleTimeString())
        } catch (error) { if (!stopped && options.onError) options.onError(error.message) }
        finally { busy = false }
      }
      const timer = win.setInterval(tick, 10000)
      tick()
      return { stop() { stopped = true; win.clearInterval(timer) } }
    }
  }
})(window)
