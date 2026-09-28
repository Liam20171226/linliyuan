/**
 * 小程序 API 基址配置。
 *
 * 上线前：把 PROD_API_BASE 改成正式 HTTPS（须含 /api/v1），
 * 并在微信公众平台配置 request / uploadFile 合法域名。
 *
 * 开发：可用登录页「开发调试」覆盖；或改 DEV_API_BASE。
 */
const PROD_API_BASE = 'https://YOUR_API_DOMAIN/api/v1'
const DEV_API_BASE = 'http://192.168.137.1:8080/api/v1'

function resolveDefaultApiBase() {
  try {
    const info = wx.getAccountInfoSync && wx.getAccountInfoSync()
    const env = info && info.miniProgram && info.miniProgram.envVersion
    // develop / trial 用开发地址；release 用正式域名
    if (env === 'release') {
      return PROD_API_BASE.replace(/\/$/, '')
    }
  } catch (e) { /* ignore */ }
  return DEV_API_BASE.replace(/\/$/, '')
}

function isDevToolsEnv() {
  try {
    const info = wx.getAccountInfoSync && wx.getAccountInfoSync()
    const env = info && info.miniProgram && info.miniProgram.envVersion
    return env === 'develop' || env === 'trial'
  } catch (e) {
    return true
  }
}

module.exports = {
  PROD_API_BASE,
  DEV_API_BASE,
  resolveDefaultApiBase,
  isDevToolsEnv
}
