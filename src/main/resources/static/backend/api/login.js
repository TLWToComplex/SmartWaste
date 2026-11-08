function loginApi(data) {
  return $axios({
    'url': '/employee/login',
    'method': 'post',
    data
  })
}

function logoutApi(){
  return SmartWasteAuth.logout('admin').then(() => ({ code: 1 }))
}
