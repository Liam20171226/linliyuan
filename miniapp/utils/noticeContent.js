/** 公告正文：HTML 富文本 / 旧版 {{IMG:id}} */

function apiBaseAndToken() {
  const app = getApp()
  const token = (app && app.globalData && app.globalData.token) || wx.getStorageSync('token') || ''
  const base = (app && app.globalData && app.globalData.apiBase) || ''
  return { base, token }
}

function attachmentSrc(id) {
  const { base, token } = apiBaseAndToken()
  if (!id || !base) return ''
  return `${base}/attachments/${id}${token ? `?token=${encodeURIComponent(token)}` : ''}`
}

function hasNoticeBody(html) {
  const raw = (html || '').trim()
  if (!raw) return false
  if (
    /<img[\s>]/i.test(raw) ||
    /attachment:\/\//i.test(raw) ||
    /\{\{IMG:\d+\}\}/.test(raw) ||
    /\{\{FILE:\d+:/.test(raw)
  ) {
    return true
  }
  const text = raw.replace(/<[^>]+>/g, ' ').replace(/&nbsp;/gi, ' ').replace(/\s+/g, ' ').trim()
  return text.length > 0
}

function imgMarker(id) {
  return `{{IMG:${id}}}`
}

function attrOf(tag, name) {
  const re = new RegExp(name + '=["\']([^"\']*)["\']', 'i')
  const m = String(tag).match(re)
  return m ? m[1] : ''
}

function idFromUrl(url) {
  const m = String(url || '').match(/attachment:\/\/(\d+)/i) || String(url || '').match(/\/attachments\/(\d+)/i)
  return m ? Number(m[1]) : 0
}

function sanitizeHtml(html) {
  return String(html || '')
    .replace(/<script[\s\S]*?<\/script>/gi, '')
    .replace(/<iframe[\s\S]*?<\/iframe>/gi, '')
    .replace(/\son\w+=(?:"[^"]*"|'[^']*')/gi, '')
}

function isBlankHtml(html) {
  const text = String(html || '')
    .replace(/<br\s*\/?>/gi, '')
    .replace(/<[^>]+>/g, '')
    .replace(/&nbsp;/gi, ' ')
    .replace(/\s+/g, '')
  return !text
}

function styleParagraphs(html) {
  return String(html || '').replace(/<p(\s|>)/gi, '<p style="margin:0 0 12px;font-size:15px;line-height:1.8;color:#333333;"$1')
}

/**
 * 详情页：文字走 rich-text，图片拆成原生 image，附件单独列出。
 * 封面不在这里，只给首页 Banner。
 */
function splitNoticeBody(content) {
  let html = content == null ? '' : String(content)
  const attachments = []
  const seen = new Set()

  function pushAtt(id, name) {
    if (!id || seen.has('a' + id)) return
    seen.add('a' + id)
    attachments.push({ id, name: name || '附件', src: attachmentSrc(id) })
  }

  if (/\{\{IMG:\d+\}\}/.test(html) && !/<img[\s>]/i.test(html)) {
    const parts = html.split(/(\{\{IMG:\d+\}\})/g)
    const chunks = []
    for (const part of parts) {
      const m = part.match(/^\{\{IMG:(\d+)\}\}$/)
      if (m) chunks.push(`<img src="attachment://${m[1]}" data-attachment-id="${m[1]}"/>`)
      else if (part) chunks.push(`<p>${escapeText(part).replace(/\n/g, '<br/>')}</p>`)
    }
    html = chunks.join('')
  }

  // {{FILE:id:name}} → 附件列表，并从正文移除（避免当纯文本显示）
  html = html.replace(/\{\{FILE:(\d+):([\s\S]*?)\}\}/g, (_full, idStr, name) => {
    pushAtt(Number(idStr), String(name || '附件').trim())
    return ''
  })

  // 旧版 wangEditor attachment 节点
  html = html.replace(/<a\b[^>]*data-w-e-type=["']attachment["'][^>]*>[\s\S]*?<\/a>/gi, (full) => {
    const href = attrOf(full, 'href') || attrOf(full, 'data-link')
    const name = attrOf(full, 'download') || attrOf(full, 'data-fileName') || attrOf(full, 'data-filename') || '附件'
    pushAtt(idFromUrl(href), name)
    return ''
  })

  // 普通 attachment:// 链接
  html = html.replace(/<a\b[^>]*href=["']attachment:\/\/(\d+)["'][^>]*>[\s\S]*?<\/a>/gi, (full, idStr) => {
    const name =
      attrOf(full, 'download') ||
      String(full.replace(/<[^>]+>/g, '')).trim() ||
      '附件'
    pushAtt(Number(idStr), name)
    return ''
  })

  // 清掉因抽取附件留下的空段落
  html = html.replace(/<p>(?:\s|&nbsp;|<br\s*\/?>)*<\/p>/gi, '')

  html = sanitizeHtml(html)
  const blocks = []
  const imgRe = /<img\b[^>]*>/gi
  let last = 0
  let m
  let n = 0
  while ((m = imgRe.exec(html)) !== null) {
    pushText(html.slice(last, m.index))
    const tag = m[0]
    const id = Number(attrOf(tag, 'data-attachment-id')) || idFromUrl(attrOf(tag, 'src'))
    const rawSrc = attrOf(tag, 'src')
    const src = id ? attachmentSrc(id) : (/^https?:/i.test(rawSrc) ? rawSrc : '')
    if (src) blocks.push({ key: 'img-' + n++, type: 'img', src })
    last = m.index + tag.length
  }
  pushText(html.slice(last))

  function pushText(chunk) {
    const cleaned = styleParagraphs(sanitizeHtml(chunk))
    if (isBlankHtml(cleaned)) return
    blocks.push({ key: 't-' + n++, type: 'text', html: cleaned })
  }

  return { blocks, attachments }
}

function prepareNoticeHtml(content) {
  const split = splitNoticeBody(content)
  return {
    html: split.blocks.filter((b) => b.type === 'text').map((b) => b.html).join('') || '<p>暂无正文</p>',
    attachments: split.attachments,
    blocks: split.blocks
  }
}

function escapeText(s) {
  return String(s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

/** 兼容旧 parseNoticeContent：仅拆文本/图块（无 HTML 时） */
function parseNoticeContent(content) {
  const raw = content == null ? '' : String(content)
  if (/<[a-z][\s\S]*>/i.test(raw)) {
    return [{ type: 'html', value: prepareNoticeHtml(raw).html }]
  }
  const blocks = []
  let last = 0
  const re = /\{\{IMG:(\d+)\}\}/g
  let m
  while ((m = re.exec(raw)) !== null) {
    if (m.index > last) {
      const text = raw.slice(last, m.index)
      if (text.length) blocks.push({ type: 'text', value: text })
    }
    const id = Number(m[1])
    blocks.push({ type: 'img', id, src: attachmentSrc(id) })
    last = m.index + m[0].length
  }
  if (last < raw.length) {
    const text = raw.slice(last)
    if (text.length) blocks.push({ type: 'text', value: text })
  }
  return blocks
}

module.exports = {
  hasNoticeBody,
  imgMarker,
  parseNoticeContent,
  prepareNoticeHtml,
  splitNoticeBody,
  attachmentSrc
}
