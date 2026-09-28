const { request } = require('../../utils/request')
const { repairStatusLabel, complaintStatusLabel, formatDateTime } = require('../../utils/format')

/** 报事报修的 5 类（与 service-desk / service-submit 的 CATEGORY_DEFS 一致） */
const CATS = [
  { key: 'public', name: '公区报障', kind: 'repair' },
  { key: 'indoor', name: '室内维修', kind: 'repair' },
  { key: 'complaint', name: '投诉', kind: 'complaint' },
  { key: 'praise', name: '表扬', kind: 'complaint' },
  { key: 'advice', name: '咨询建议', kind: 'complaint' }
]

function catOf(name, kind) {
  const hit = CATS.find((c) => c.name === name)
  if (hit) return hit
  return CATS.find((c) => c.kind === kind) || CATS[0]
}

Page({
  data: {
    all: [],
    list: [],
    chips: [],
    filter: 'ALL',
    loading: true
  },

  onShow() {
    this.load()
  },
  onPullDownRefresh() {
    this.load().finally(() => wx.stopPullDownRefresh())
  },

  onFilter(e) {
    const f = e.currentTarget.dataset.f || 'ALL'
    this.setData({ filter: f })
    this.applyFilter(f)
  },

  applyFilter(f) {
    const all = this.data.all || []
    this.setData({ list: f === 'ALL' ? all : all.filter((it) => it.catKey === f) })
  },

  async load() {
    // 报修（公区报障 / 室内维修）与投诉类（投诉 / 表扬 / 咨询建议）并行拉取
    const [repairs, complaints] = await Promise.all([
      request({ url: '/resident/repairs', data: { page: 1, pageSize: 50 } }).catch(() => null),
      request({ url: '/resident/complaints', data: { page: 1, pageSize: 50 } }).catch(() => null)
    ])

    const merged = []
    ;((repairs && repairs.list) || []).forEach((it) => {
      const cat = catOf(it.category, 'repair')
      merged.push({
        key: 'R' + it.id,
        id: it.id,
        kind: 'repair',
        catKey: cat.key,
        catLabel: cat.name,
        title: it.category || cat.name,
        desc: it.content || '',
        location: it.location || '',
        reply: '',
        statusLabel: repairStatusLabel(it.status),
        canRate: it.status === 'DONE_WAIT_RATE' && it.category !== '表扬',
        time: fmt(it.createdAt),
        createdAt: it.createdAt || ''
      })
    })
    ;((complaints && complaints.list) || []).forEach((it) => {
      const cat = catOf(it.category, 'complaint')
      merged.push({
        key: 'C' + it.id,
        id: it.id,
        kind: 'complaint',
        catKey: cat.key,
        catLabel: cat.name,
        title: it.category || cat.name,
        desc: it.content || '',
        location: '',
        reply: it.replyContent || '',
        statusLabel: complaintStatusLabel(it.status),
        canRate: it.status === 'DONE_WAIT_RATE' && it.category !== '表扬',
        time: fmt(it.createdAt),
        createdAt: it.createdAt || ''
      })
    })

    merged.sort((a, b) => String(b.createdAt).localeCompare(String(a.createdAt)))

    const chips = [{ key: 'ALL', name: '全部', count: merged.length }].concat(
      CATS.map((c) => ({
        key: c.key,
        name: c.name,
        count: merged.filter((it) => it.catKey === c.key).length
      }))
    )

    const filter = this.data.filter
    this.setData({
      all: merged,
      chips,
      loading: false,
      list: filter === 'ALL' ? merged : merged.filter((it) => it.catKey === filter)
    })
  },

  openDetail(e) {
    const { id, kind, catkey } = e.currentTarget.dataset
    wx.navigateTo({
      url: `/pages/service-detail/service-detail?id=${id}&kind=${kind}&catKey=${catkey}`
    })
  },

  /** 列表「去评价」：进入详情页完整评价，不再一键五星 */
  goRate(e) {
    const id = e.currentTarget.dataset.id
    const kind = e.currentTarget.dataset.kind || 'repair'
    const catkey = e.currentTarget.dataset.catkey || ''
    wx.navigateTo({
      url: `/pages/service-detail/service-detail?id=${id}&kind=${kind}&catKey=${catkey}`
    })
  }
})

function fmt(v) {
  const s = formatDateTime(v)
  return s ? String(s).slice(0, 16) : ''
}
