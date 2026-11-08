// 查询全部订单的状态数量及今日下单量，不受列表筛选和分页影响。
const getOrderStatistics = () => {
  return $axios({
    url: '/order/statistics',
    method: 'get'
  })
}

const getOverdueOrderReminders = (params) => {
  return $axios({
    url: '/order/overdue-reminders',
    method: 'get',
    params
  })
}

// 查询列表页接口
const getOrderDetailPage = (params) => {
  return $axios({
    url: '/order/page',
    method: 'get',
    params
  })
}

// 查看接口
const queryOrderDetailById = (id) => {
  return $axios({
    url: `/orderDetail/${id}`,
    method: 'get'
  })
}

// 取消，派送，完成接口
const editOrderDetail = (params) => {
  return $axios({
    url: '/order',
    method: 'put',
    data: { ...params }
  })
}
