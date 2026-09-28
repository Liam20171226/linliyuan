import axios from 'axios'

/** 开发默认本地；生产构建用 .env.production 的 VITE_API_BASE */
export const API_BASE = (import.meta.env.VITE_API_BASE as string | undefined)?.replace(/\/$/, '')
  || 'http://127.0.0.1:8080/api/v1'

export const api = axios.create({ baseURL: API_BASE })

api.interceptors.request.use((cfg) => {
  const token = localStorage.getItem('token')
  if (token) cfg.headers.Authorization = `Bearer ${token}`
  return cfg
})
