/** 平台 / 物业会话标记（localStorage） */
const ROLE_KEY = 'adminRole' // 'platform' | 'staff'
const COMMUNITY_NAME_KEY = 'communityName'
const COMMUNITY_ID_KEY = 'communityId'

export function setPlatformSession(token: string) {
  localStorage.setItem('token', token)
  localStorage.setItem(ROLE_KEY, 'platform')
  localStorage.removeItem(COMMUNITY_NAME_KEY)
  localStorage.removeItem(COMMUNITY_ID_KEY)
}

export function setStaffSession(token: string, communityName?: string, communityId?: number | string) {
  localStorage.setItem('token', token)
  localStorage.setItem(ROLE_KEY, 'staff')
  if (communityName) localStorage.setItem(COMMUNITY_NAME_KEY, communityName)
  if (communityId != null) localStorage.setItem(COMMUNITY_ID_KEY, String(communityId))
}

export function setPlatformInCommunity(token: string, communityName: string, communityId: number | string) {
  localStorage.setItem('token', token)
  localStorage.setItem(ROLE_KEY, 'platform')
  localStorage.setItem(COMMUNITY_NAME_KEY, communityName)
  localStorage.setItem(COMMUNITY_ID_KEY, String(communityId))
}

export function isPlatformRole() {
  return localStorage.getItem(ROLE_KEY) === 'platform'
}

export function communityLabel() {
  return localStorage.getItem(COMMUNITY_NAME_KEY) || ''
}

export function authFailRedirectPath() {
  return isPlatformRole() ? '/communities' : '/staff-login'
}
