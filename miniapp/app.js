const { resolveDefaultApiBase } = require('./config')

App({
  globalData: {
    // 默认见 config.js；正式版走 PROD_API_BASE；开发可登录页覆盖
    apiBase: resolveDefaultApiBase(),
    token: '',
    identities: []
  },
  onLaunch() {
    try {
      const override = wx.getStorageSync('apiBaseOverride')
      if (override && typeof override === 'string' && override.trim()) {
        this.globalData.apiBase = override.trim().replace(/\/$/, '')
      } else {
        this.globalData.apiBase = resolveDefaultApiBase()
      }
    } catch (e) { /* ignore */ }
    const token = wx.getStorageSync('token')
    if (token) {
      this.globalData.token = token
      // 延迟加载，避免 app.js 顶层 require 触发 request 里过早 getApp()
      try {
        const { restorePreferredResidentIfNeeded } = require('./utils/identity')
        restorePreferredResidentIfNeeded().catch(() => {})
      } catch (e) { /* ignore */ }
    }
  }
})
