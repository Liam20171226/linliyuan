import { getApiBase } from './config'
import { getToken } from './auth'

export type UploadedFile = {
  id: number
  fileName?: string
  localPath?: string
}

/** 上传本地图片到 /attachments/upload（bizType=TEMP） */
export function uploadTempFile(filePath: string): Promise<UploadedFile> {
  const apiBase = getApiBase()
  const token = getToken()
  return new Promise((resolve, reject) => {
    uni.uploadFile({
      url: `${apiBase}/attachments/upload`,
      filePath,
      name: 'file',
      formData: { bizType: 'TEMP' },
      header: token ? { Authorization: `Bearer ${token}` } : {},
      success: (r) => {
        try {
          const body = JSON.parse(String(r.data || '{}')) as {
            code?: number
            message?: string
            data?: { id?: number; fileName?: string }
          }
          if (body.code === 0 && body.data && body.data.id != null) {
            resolve({
              id: Number(body.data.id),
              fileName: body.data.fileName,
              localPath: filePath,
            })
          } else {
            reject(new Error(body.message || '上传失败'))
          }
        } catch (e) {
          reject(e instanceof Error ? e : new Error('上传响应解析失败'))
        }
      },
      fail: (err) => {
        reject(new Error((err && err.errMsg) || '上传失败'))
      },
    })
  })
}

export function deleteAttachment(id: number): Promise<void> {
  const apiBase = getApiBase()
  const token = getToken()
  return new Promise((resolve) => {
    uni.request({
      url: `${apiBase}/attachments/${id}`,
      method: 'DELETE',
      header: token ? { Authorization: `Bearer ${token}` } : {},
      complete: () => resolve(),
    })
  })
}

export function attachmentUrl(id: number): string {
  const token = getToken()
  const base = getApiBase()
  return `${base}/attachments/${id}${token ? `?token=${encodeURIComponent(token)}` : ''}`
}

/** 选图并上传，最多 max 张；返回累计列表 */
export async function chooseAndUpload(
  current: UploadedFile[],
  max = 3,
): Promise<UploadedFile[]> {
  const remain = max - current.length
  if (remain <= 0) {
    uni.showToast({ title: `最多 ${max} 张`, icon: 'none' })
    return current
  }
  const paths: string[] = await new Promise((resolve, reject) => {
    uni.chooseImage({
      count: remain,
      sizeType: ['compressed'],
      sourceType: ['album', 'camera'],
      success: (res) => resolve(res.tempFilePaths || []),
      fail: (err) => {
        if (err && String(err.errMsg || '').includes('cancel')) resolve([])
        else reject(err)
      },
    })
  })
  if (!paths.length) return current
  uni.showLoading({ title: '上传中', mask: true })
  try {
    const next = current.slice()
    for (const path of paths) {
      if (next.length >= max) break
      const uploaded = await uploadTempFile(path)
      next.push(uploaded)
    }
    return next
  } finally {
    uni.hideLoading()
  }
}
