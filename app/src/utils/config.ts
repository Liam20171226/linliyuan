/** 开发默认：本机后端。真机/模拟器请改成电脑局域网 IP，如 http://192.168.137.1:8080/api/v1 */
export const DEFAULT_API_BASE = 'http://127.0.0.1:8080/api/v1'

const KEY = 'apiBase'

export function getApiBase(): string {
  const stored = uni.getStorageSync(KEY)
  if (typeof stored === 'string' && stored.trim()) {
    return stored.trim().replace(/\/$/, '')
  }
  return DEFAULT_API_BASE
}

export function setApiBase(base: string) {
  uni.setStorageSync(KEY, base.trim().replace(/\/$/, ''))
}
