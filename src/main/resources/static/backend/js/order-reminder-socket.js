(function (win) {
  win.createOrderReminderSocket = function (options) {
    return win.SmartWasteAuth.openSocket('/ws/admin/order-reminders', {
      onState: options.onState,
      onReady: options.onRefresh,
      onMessage(message) {
        if (message.type === 'order-reminders.updated' && options.onRefresh) options.onRefresh()
        if (message.type === 'order-reminders.error' && options.onScanError) options.onScanError()
      }
    })
  }
})(window)
