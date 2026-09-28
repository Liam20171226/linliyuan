/** 公告正文：HTML 富文本；图片 attachment://{id}；非图附件 {{FILE:id:文件名}} */

const ATTACH_RE = /attachment:\/\/(\d+)/gi
const API_ATTACH_RE = /\/api\/v1\/attachments\/(\d+)/gi
const LEGACY_IMG_RE = /\{\{IMG:(\d+)\}\}/g
const FILE_MARKER_RE = /\{\{FILE:(\d+):([\s\S]*?)\}\}/g

export function fileMarker(id: number, name: string): string {
  const safe = String(name || '附件').replace(/\}\}/g, '')
  return `{{FILE:${id}:${safe}}}`
}

export function hasNoticeBody(html: string | null | undefined): boolean {
  const raw = (html || '').trim()
  if (!raw) return false
  if (
    /<img[\s>]/i.test(raw) ||
    /data-w-e-type=["']attachment["']/i.test(raw) ||
    ATTACH_RE.test(raw) ||
    /\{\{FILE:\d+:/i.test(raw)
  ) {
    ATTACH_RE.lastIndex = 0
    return true
  }
  const text = raw
    .replace(/<[^>]+>/g, ' ')
    .replace(/&nbsp;/gi, ' ')
    .replace(/\s+/g, ' ')
    .trim()
  return text.length > 0
}

export function extractAttachmentIds(html: string | null | undefined): number[] {
  const raw = html || ''
  const ids: number[] = []
  const seen = new Set<number>()
  const push = (s: string) => {
    const id = Number(s)
    if (!id || seen.has(id)) return
    seen.add(id)
    ids.push(id)
  }
  let m: RegExpExecArray | null
  const re1 = new RegExp(ATTACH_RE.source, 'gi')
  while ((m = re1.exec(raw)) !== null) push(m[1])
  const re2 = new RegExp(API_ATTACH_RE.source, 'gi')
  while ((m = re2.exec(raw)) !== null) push(m[1])
  const re3 = new RegExp(LEGACY_IMG_RE.source, 'g')
  while ((m = re3.exec(raw)) !== null) push(m[1])
  const re4 = new RegExp(FILE_MARKER_RE.source, 'g')
  while ((m = re4.exec(raw)) !== null) push(m[1])
  return ids
}

export function extractFileMarkers(html: string | null | undefined): { id: number; name: string }[] {
  const raw = html || ''
  const out: { id: number; name: string }[] = []
  const seen = new Set<number>()
  const re = new RegExp(FILE_MARKER_RE.source, 'g')
  let m: RegExpExecArray | null
  while ((m = re.exec(raw)) !== null) {
    const id = Number(m[1])
    if (!id || seen.has(id)) continue
    seen.add(id)
    out.push({ id, name: m[2] || '附件' })
  }
  const aRe = /<a\b[^>]*href=["']attachment:\/\/(\d+)["'][^>]*>([\s\S]*?)<\/a>/gi
  while ((m = aRe.exec(raw)) !== null) {
    const id = Number(m[1])
    if (!id || seen.has(id)) continue
    seen.add(id)
    const name =
      (m[0].match(/\bdownload=["']([^"']+)["']/i) || [])[1] ||
      String(m[2] || '')
        .replace(/<[^>]+>/g, '')
        .trim() ||
      '附件'
    out.push({ id, name })
  }
  return out
}

/** 旧版 {{IMG:id}} → HTML；{{FILE:id:name}} → 可点链接，便于编辑器打开 */
export function legacyToHtml(content: string | null | undefined): string {
  let raw = content == null ? '' : String(content)
  if (!raw.trim()) return ''

  raw = raw.replace(FILE_MARKER_RE, (_m, id, name) => {
    const n = escapeHtml(String(name || '附件'))
    return `<p><a href="attachment://${id}" download="${n}">${n}</a></p>`
  })

  if (/<[a-z][\s\S]*>/i.test(raw) && !LEGACY_IMG_RE.test(raw)) {
    LEGACY_IMG_RE.lastIndex = 0
    return raw
  }
  LEGACY_IMG_RE.lastIndex = 0
  if (!/\{\{IMG:\d+\}\}/.test(raw) && !/<[a-z]/i.test(raw)) {
    return `<p>${escapeHtml(raw).replace(/\n/g, '<br>')}</p>`
  }
  const parts = raw.split(/(\{\{IMG:\d+\}\})/g)
  const chunks: string[] = []
  for (const part of parts) {
    const m = part.match(/^\{\{IMG:(\d+)\}\}$/)
    if (m) {
      chunks.push(`<p><img src="attachment://${m[1]}" alt="图片" data-attachment-id="${m[1]}"/></p>`)
    } else if (part.trim()) {
      chunks.push(part.includes('<') ? part : `<p>${escapeHtml(part).replace(/\n/g, '<br>')}</p>`)
    }
  }
  return chunks.join('') || '<p><br></p>'
}

export function escapeHtml(s: string): string {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

/** 保存前：blob → attachment://；附件链接压成 {{FILE:id:name}}（防编辑器丢自定义节点） */
export function toStorageHtml(html: string, blobToId: Map<string, number>): string {
  let out = html || ''
  for (const [blobUrl, id] of blobToId.entries()) {
    if (!blobUrl) continue
    out = out.split(blobUrl).join(`attachment://${id}`)
  }
  out = out.replace(API_ATTACH_RE, (_m, id) => `attachment://${id}`)
  out = out.replace(/<img\b([^>]*)\/?\s*>/gi, (full, attrs: string) => {
    const clean = String(attrs || '').replace(/\s*\/\s*$/, '').trimEnd()
    const srcM = clean.match(/\bsrc=["']([^"']+)["']/i)
    if (!srcM) return full
    const src = srcM[1]
    let id: number | null = null
    const a = src.match(/^attachment:\/\/(\d+)$/i)
    if (a) id = Number(a[1])
    if (id && !/data-attachment-id=/i.test(clean)) {
      return `<img ${clean.trim()} data-attachment-id="${id}">`
    }
    if (id) {
      return `<img ${clean.trim()}>`
    }
    return full
  })
  out = out.replace(/<a\b[^>]*href=["']attachment:\/\/(\d+)["'][^>]*>[\s\S]*?<\/a>/gi, (full, idStr) => {
    const id = Number(idStr)
    const name =
      (full.match(/\bdownload=["']([^"']+)["']/i) || [])[1] ||
      String(full.replace(/<[^>]+>/g, '')).trim() ||
      '附件'
    return fileMarker(id, name)
  })
  return out
}

/** 展示/编辑：attachment://id → 可见 URL（blob 或带 token 的接口地址） */
export function rewriteAttachmentUrls(
  html: string,
  resolveUrl: (id: number) => string,
): string {
  let out = html || ''
  out = out.replace(ATTACH_RE, (_m, id) => resolveUrl(Number(id)))
  out = out.replace(API_ATTACH_RE, (_m, id) => resolveUrl(Number(id)))
  return out
}

export function plainTextFromHtml(html: string): string {
  return (html || '')
    .replace(/<style[\s\S]*?<\/style>/gi, '')
    .replace(/<script[\s\S]*?<\/script>/gi, '')
    .replace(/<[^>]+>/g, ' ')
    .replace(/&nbsp;/gi, ' ')
    .replace(/\s+/g, ' ')
    .trim()
}
