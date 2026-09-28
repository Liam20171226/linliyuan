<script setup lang="ts">
import '@wangeditor/editor/dist/css/style.css'
import { onBeforeUnmount, shallowRef, watch, ref } from 'vue'
import { Editor, Toolbar } from '@wangeditor/editor-for-vue'
import type { IDomEditor, IEditorConfig, IToolbarConfig } from '@wangeditor/editor'
import { ElMessage } from 'element-plus'
import { api } from '../api'
import { legacyToHtml, toStorageHtml, fileMarker, extractFileMarkers } from '../noticeContent'

const props = defineProps<{
  modelValue: string
  /** blobUrl → attachmentId，由父级与保存逻辑共享 */
  blobToId: Map<string, number>
  /** 上传业务类型，默认公告 */
  bizType?: string
  /** 已知业务主键（如关于我们=1），上传时直接绑定，避免保存丢链 */
  bizId?: number
}>()

const emit = defineEmits<{
  'update:modelValue': [string]
}>()

const editorRef = shallowRef<IDomEditor | undefined>()
const html = ref('')
const applying = ref(false)

/** 不依赖附件插件菜单，避免 uploadAttachment 未注册时整页崩溃 */
const toolbarConfig: Partial<IToolbarConfig> = {
  excludeKeys: [
    'group-video',
    'insertVideo',
    'uploadVideo',
    'editVideoSize',
    'fullScreen',
    'codeBlock',
    'todo',
    'emotion',
  ],
}

const imgInputRef = ref<HTMLInputElement | null>(null)
const fileInputRef = ref<HTMLInputElement | null>(null)
const busy = ref(false)

async function uploadFile(file: File): Promise<{ id: number; name: string; blobUrl: string }> {
  const body = new FormData()
  body.append('file', file)
  body.append('bizType', props.bizType || 'NOTICE')
  if (props.bizId != null && props.bizId > 0) {
    body.append('bizId', String(props.bizId))
  }
  const { data } = await api.post('/attachments/upload', body, {
    headers: { 'Content-Type': 'multipart/form-data' },
  })
  if (data.code !== 0 || !data.data?.id) {
    throw new Error(data.message || '上传失败')
  }
  const id = Number(data.data.id)
  const name = String(data.data.fileName || file.name || 'file')
  const blobRes = await api.get(`/attachments/${id}`, { responseType: 'blob' })
  const blobUrl = URL.createObjectURL(blobRes.data)
  props.blobToId.set(blobUrl, id)
  return { id, name, blobUrl }
}

function pickImage() {
  imgInputRef.value?.click()
}
function pickAttachment() {
  fileInputRef.value?.click()
}

function escapeAttr(s: string) {
  return s.replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;')
}

async function onImgPicked(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  busy.value = true
  try {
    const { blobUrl, name, id } = await uploadFile(file)
    const ed = editorRef.value
    if (ed) {
      ed.focus()
      ed.dangerouslyInsertHtml(
        `<p><img src="${blobUrl}" alt="${escapeAttr(name)}" data-attachment-id="${id}"/></p>`,
      )
      html.value = ed.getHtml()
      emit('update:modelValue', toStorageHtml(html.value, props.blobToId))
    }
    ElMessage.success('图片已插入')
  } catch (err: any) {
    ElMessage.error(err?.message || '图片上传失败')
  } finally {
    busy.value = false
  }
}

async function onFilePicked(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  busy.value = true
  try {
    const { id, name } = await uploadFile(file)
    const ed = editorRef.value
    // 用纯文本标记，避免 wangEditor getHtml 丢掉自定义 attachment 节点
    const marker = fileMarker(id, name)
    if (ed) {
      ed.focus()
      ed.dangerouslyInsertHtml(`<p>${marker}</p>`)
      html.value = ed.getHtml()
      emit('update:modelValue', ensureFileMarkers(toStorageHtml(html.value, props.blobToId), [{ id, name }]))
    } else {
      emit('update:modelValue', ensureFileMarkers(props.modelValue || '', [{ id, name }]))
    }
    ElMessage.success('附件已插入')
  } catch (err: any) {
    ElMessage.error(err?.message || '附件上传失败')
  } finally {
    busy.value = false
  }
}

function ensureFileMarkers(html: string, extra: { id: number; name: string }[] = []): string {
  let out = html || ''
  const known = extractFileMarkers(out)
  const byId = new Map(known.map((f) => [f.id, f]))
  for (const f of extra) byId.set(f.id, f)
  for (const f of byId.values()) {
    const marker = fileMarker(f.id, f.name)
    if (!out.includes(`attachment://${f.id}`) && !out.includes(marker) && !out.includes(`{{FILE:${f.id}:`)) {
      out += `<p>${marker}</p>`
    }
  }
  return out
}

const editorConfig: Partial<IEditorConfig> = {
  placeholder: '输入公告正文，可设置字体/字号；上方按钮可插入图片与附件…',
  MENU_CONF: {
    fontSize: {
      fontSizeList: ['12px', '14px', '16px', '18px', '20px', '24px', '28px', '32px'],
    },
    fontFamily: {
      fontFamilyList: [
        '黑体',
        '楷体',
        '仿宋',
        '微软雅黑',
        '宋体',
        'Arial',
        'Tahoma',
        'Verdana',
      ],
    },
    uploadImage: {
      allowedFileTypes: ['image/*'],
      async customUpload(file: File, insertFn: (url: string, alt: string, href: string) => void) {
        try {
          const { blobUrl, name } = await uploadFile(file)
          insertFn(blobUrl, name, blobUrl)
        } catch (e: any) {
          ElMessage.error(e?.message || '图片上传失败')
        }
      },
    },
  },
}

async function hydrateFromModel(raw: string) {
  applying.value = true
  try {
    let next = legacyToHtml(raw)
    const ids = [...next.matchAll(/attachment:\/\/(\d+)/gi)].map((m) => Number(m[1]))
    const uniq = [...new Set(ids)]
    for (const id of uniq) {
      let blobUrl = ''
      for (const [b, aid] of props.blobToId.entries()) {
        if (aid === id) {
          blobUrl = b
          break
        }
      }
      if (!blobUrl) {
        try {
          const blobRes = await api.get(`/attachments/${id}`, { responseType: 'blob' })
          blobUrl = URL.createObjectURL(blobRes.data)
          props.blobToId.set(blobUrl, id)
        } catch {
          continue
        }
      }
      next = next.split(`attachment://${id}`).join(blobUrl)
    }
    html.value = next || '<p><br></p>'
  } finally {
    applying.value = false
  }
}

watch(
  () => props.modelValue,
  (v) => {
    if (applying.value) return
    const storage = toStorageHtml(html.value, props.blobToId)
    if (storage === (v || '')) return
    hydrateFromModel(v || '')
  },
  { immediate: true },
)

function onCreated(editor: IDomEditor) {
  editorRef.value = editor
}

function onChange(editor: IDomEditor) {
  if (applying.value) return
  html.value = editor.getHtml()
  emit('update:modelValue', toStorageHtml(html.value, props.blobToId))
}

onBeforeUnmount(() => {
  const ed = editorRef.value
  if (ed) ed.destroy()
})

defineExpose({
  getStorageHtml: () => ensureFileMarkers(toStorageHtml(html.value, props.blobToId), extractFileMarkers(html.value)),
})
</script>

<template>
  <div class="rich-wrap">
    <div class="rich-actions">
      <el-button size="small" :loading="busy" @click="pickImage">插入图片</el-button>
      <el-button size="small" type="primary" plain :loading="busy" @click="pickAttachment">上传附件</el-button>
      <span class="rich-tip">字体/字号在下方工具栏；图片也可点工具栏「图片」图标</span>
    </div>
    <input ref="imgInputRef" class="hidden-file" type="file" accept="image/*" @change="onImgPicked" />
    <input
      ref="fileInputRef"
      class="hidden-file"
      type="file"
      accept=".pdf,.doc,.docx,.xls,.xlsx,.zip,image/*"
      @change="onFilePicked"
    />
    <Toolbar class="rich-toolbar" :editor="editorRef" :default-config="toolbarConfig" mode="default" />
    <Editor
      class="rich-editor"
      v-model="html"
      :default-config="editorConfig"
      mode="default"
      @on-created="onCreated"
      @on-change="onChange"
    />
  </div>
</template>

<style scoped>
.rich-wrap {
  border: 1px solid #dfe6e1;
  border-radius: 8px;
  overflow: hidden;
  background: #fff;
}
.rich-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  padding: 10px 12px;
  background: #f7faf8;
  border-bottom: 1px solid #e8eeea;
}
.rich-tip {
  font-size: 12px;
  color: #889;
}
.hidden-file {
  display: none;
}
.rich-toolbar {
  border-bottom: 1px solid #e8eeea;
}
.rich-editor {
  min-height: 320px;
  overflow-y: auto;
}
.rich-editor :deep(.w-e-text-container) {
  min-height: 320px !important;
}
</style>
