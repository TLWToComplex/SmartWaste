(function (win) {
  let loading
  async function sdk() {
    if (win.AMap) return win.AMap
    if (loading) return loading
    loading = (async () => {
      const config = await WasteWorkflow.request('/transport/map-config')
      if (!config.jsKey) throw new Error('请配置高德地图 JS Key；配置后可显示地图并开启定位。')
      win._AMapSecurityConfig = { securityJsCode: config.securityJsCode }
      await new Promise((resolve, reject) => {
        const script = document.createElement('script')
        script.src = 'https://webapi.amap.com/maps?v=2.0&key=' + encodeURIComponent(config.jsKey) + '&plugin=AMap.Geolocation'
        script.onload = () => win.AMap ? resolve() : reject(new Error('高德地图加载失败'))
        script.onerror = () => { script.remove(); reject(new Error('高德地图加载失败，请检查网络与 Key 的域名限制')) }
        document.head.appendChild(script)
      })
      return win.AMap
    })().catch(error => { loading = null; throw error })
    return loading
  }
  function points(polyline) { return (polyline || '').split(';').map(s => s.split(',').map(Number)).filter(p => p.length === 2 && p.every(Number.isFinite)) }
  function drawRoute(map, route) {
    const path = points(route.polyline)
    if (!path.length) return []
    const overlays = [new AMap.Polyline({ path, strokeColor: '#2580e8', strokeWeight: 6 }),
      new AMap.Marker({ position: path[0], title: '垃圾桶' }), new AMap.Marker({ position: path[path.length - 1], title: '垃圾站' })]
    map.add(overlays); map.setFitView(overlays)
    return overlays
  }
  function navigation(coordinate, name) {
    return 'https://uri.amap.com/navigation?to=' + encodeURIComponent(coordinate + ',' + name) + '&mode=car&policy=1&src=SmartWaste&coordinate=gaode&callnative=1'
  }
  win.WasteMap = { sdk, points, drawRoute, navigation }
})(window)
