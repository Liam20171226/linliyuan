const TOKEN_KEY = 'token'
const USER_KEY = 'user'
const MUST_CHANGE_KEY = 'mustChangePassword'
const CONTEXT_KEY = 'currentContext'

export type CurrentContext = {
  identityType?: string
  communityId?: number | null
  communityName?: string
  staffRoles?: string[]
  roomId?: number | null
}

export function getToken(): string {
  const t = uni.getStorageSync(TOKEN_KEY)
  return typeof t === 'string' ? t : ''
}

export function setSession(payload: {
  token: string
  user?: Record<string, unknown>
  mustChangePassword?: boolean
}) {
  uni.setStorageSync(TOKEN_KEY, payload.token)
  if (payload.user) uni.setStorageSync(USER_KEY, payload.user)
  uni.setStorageSync(MUST_CHANGE_KEY, !!payload.mustChangePassword)
}

export function clearSession() {
  uni.removeStorageSync(TOKEN_KEY)
  uni.removeStorageSync(USER_KEY)
  uni.removeStorageSync(MUST_CHANGE_KEY)
  uni.removeStorageSync(CONTEXT_KEY)
}

export function setCurrentContext(ctx: CurrentContext) {
  uni.setStorageSync(CONTEXT_KEY, ctx || {})
}

export function getCurrentContext(): CurrentContext | null {
  const c = uni.getStorageSync(CONTEXT_KEY)
  return c && typeof c === 'object' ? (c as CurrentContext) : null
}

export function getUser(): Record<string, unknown> | null {
  const u = uni.getStorageSync(USER_KEY)
  return u && typeof u === 'object' ? (u as Record<string, unknown>) : null
}

export function mustChangePassword(): boolean {
  return !!uni.getStorageSync(MUST_CHANGE_KEY)
}

export function setMustChangePassword(v: boolean) {
  uni.setStorageSync(MUST_CHANGE_KEY, v)
}

export function logout() {
  clearSession()
  uni.reLaunch({ url: '/pages/login/login' })
}
