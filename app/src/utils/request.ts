import { getApiBase } from './config'
import { getToken, logout } from './auth'

export type ApiError = {
  message: string
  code?: number
  statusCode?: number
}

export function request<T = unknown>(opts: {
  url: string
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE'
  data?: unknown
  auth?: boolean
}): Promise<T> {
  const apiBase = getApiBase()
  const header: Record<string, string> = { 'Content-Type': 'application/json' }
  if (opts.auth !== false) {
    const token = getToken()
    if (token) header.Authorization = `Bearer ${token}`
  }
  return new Promise((resolve, reject) => {
    uni.request({
      url: `${apiBase}${opts.url}`,
      method: opts.method || 'GET',
      data: opts.data as UniApp.RequestOptions['data'],
      header,
      timeout: 15000,
      success: (r) => {
        const body = r.data as { code?: number; message?: string; data?: T } | string
        if (body && typeof body === 'object' && body.code === 0) {
          resolve(body.data as T)
          return
        }
        const code = body && typeof body === 'object' ? body.code : undefined
        const msg =
          (body && typeof body === 'object' && body.message) ||
          `HTTP ${r.statusCode || '?'}`
        if (code === 40101 || r.statusCode === 401) {
          logout()
        } else if (code === 40302) {
          uni.reLaunch({ url: '/pages/change-password/change-password' })
        }
        reject({ message: String(msg), code, statusCode: r.statusCode } as ApiError)
      },
      fail: (err) => {
        reject({
          message: (err && (err as UniApp.GeneralCallbackResult).errMsg) || '网络请求失败',
        } as ApiError)
      },
    })
  })
}
