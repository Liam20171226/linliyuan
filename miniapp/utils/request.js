function resolveApiBase() {
  try {
    const override = wx.getStorageSync('apiBaseOverride')
    if (override && typeof override === 'string' && override.trim()) {
      return override.trim().replace(/\/$/, '')
    }
  } catch (e) { /* ignore */ }
  const app = getApp()
  return (app && app.globalData && app.globalData.apiBase)
    || 'http://127.0.0.1:8080/api/v1'
}

function request({ url, method = 'GET', data, auth = true }) {
  const app = getApp()
  const apiBase = resolveApiBase()
  const header = { 'Content-Type': 'application/json' }
  if (auth) {
    const token = (app && app.globalData && app.globalData.token) || wx.getStorageSync('token')
    if (token) header.Authorization = `Bearer ${token}`
  }
  return new Promise((resolve, reject) => {
    wx.request({
      url: `${apiBase}${url}`,
      method,
      data,
      header,
      timeout: 10000,
      success: (r) => {
        const body = r.data
        if (body && typeof body === 'object' && body.code === 0) {
          resolve(body.data)
          return
        }
        const msg = (body && typeof body === 'object'
          && (body.message || body.msg || body.error))
          || (typeof body === 'string' ? body.slice(0, 120) : '')
          || ('HTTP ' + (r.statusCode || '?') + ' 业务失败')
        reject({
          message: String(msg),
          code: body && body.code,
          statusCode: r.statusCode,
          apiBase
        })
      },
      fail: (err) => {
        const raw = (err && (err.errMsg || err.message)) || '网络请求失败'
        reject({
          message: String(raw),
          errMsg: err && err.errMsg,
          apiBase
        })
      }
    })
  })
}

/** 探测后端是否可达（不依赖业务码） */
function pingApi(apiBase) {
  const base = (apiBase || resolveApiBase()).replace(/\/$/, '')
  return new Promise((resolve) => {
    wx.request({
      url: `${base}/auth/miniapp/code2session`,
      method: 'POST',
      data: { code: '__ping__' },
      header: { 'Content-Type': 'application/json' },
      timeout: 8000,
      success: (r) => {
        const ok = r.statusCode >= 200 && r.statusCode < 500
        resolve({
          ok,
          statusCode: r.statusCode,
          apiBase: base,
          detail: ok ? '后端已响应' : ('HTTP ' + r.statusCode)
        })
      },
      fail: (err) => {
        resolve({
          ok: false,
          apiBase: base,
          detail: (err && err.errMsg) || '连不上'
        })
      }
    })
  })
}

module.exports = { request, resolveApiBase, pingApi }
