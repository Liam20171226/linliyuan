const { request } = require('./request')

let regionIndex = null

function buildRegionIndex() {
  if (regionIndex) return regionIndex
  const { tree } = require('./regions')
  const entries = []
  ;(tree() || []).forEach((p) => {
    const pName = p && p.name ? String(p.name) : ''
    ;(p.cities || []).forEach((c) => {
      const cName = c && c.name ? String(c.name) : ''
      ;(c.districts || []).forEach((d) => {
        const dName = d && d.name ? String(d.name) : ''
        const full = pName + cName + dName
        const dedup = pName === cName ? pName + dName : full
        entries.push({ full, dedup, cityName: cName, districtName: dName, len: Math.max(full.length, dedup.length) })
      })
    })
  })
  entries.sort((a, b) => b.len - a.len)
  regionIndex = entries
  return regionIndex
}

function parseAddress(address) {
  if (!address || !String(address).trim()) return null
  const addr = String(address).trim()
  try {
    const entries = buildRegionIndex()
    for (let i = 0; i < entries.length; i++) {
      const e = entries[i]
      if (addr.startsWith(e.full) || addr.startsWith(e.dedup)) {
        return { cityName: e.cityName, districtName: e.districtName }
      }
    }
  } catch (e) {
    /* 本地省市区加载失败时用粗解析 */
  }
  const m = addr.match(/([\u4e00-\u9fa5]{2,12}?(?:市|州|地区|盟))/)
  if (m) return { cityName: m[1], districtName: '' }
  return null
}

function groupByCity(list) {
  const buckets = {}
  const other = []
  ;(list || []).forEach((c) => {
    const parsed = parseAddress(c.address)
    const row = {
      id: c.id,
      name: c.name || '小区',
      districtName: (parsed && parsed.districtName) || ''
    }
    const city = parsed && parsed.cityName
    if (!city) {
      other.push(row)
      return
    }
    if (!buckets[city]) buckets[city] = []
    buckets[city].push(row)
  })
  const cmp = (a, b) => String(a.name).localeCompare(String(b.name), 'zh')
  const cities = Object.keys(buckets)
    .sort((a, b) => a.localeCompare(b, 'zh'))
    .map((cityName) => ({
      cityName,
      communities: buckets[cityName].slice().sort(cmp)
    }))
  if (other.length) {
    cities.push({ cityName: '其他', communities: other.slice().sort(cmp) })
  }
  return cities
}

async function fetchAllCommunities() {
  const all = []
  let page = 1
  let total = Infinity
  while (page <= 40 && all.length < total) {
    const data = await request({
      url: '/resident/communities',
      data: { page, pageSize: 50 }
    })
    const list = (data && data.list) || []
    total = Number((data && data.total) != null ? data.total : list.length)
    all.push.apply(all, list)
    if (!list.length || list.length < 50) break
    page += 1
  }
  return all
}

/** 优先新目录接口；旧后端无该接口时，用小区搜索并按城市分组 */
async function loadCitiesCatalog() {
  try {
    const data = await request({ url: '/resident/communities/catalog' })
    if (data && Array.isArray(data.cities)) return data.cities
  } catch (e) {
    /* 旧服务无 catalog，走兼容路径 */
  }
  const list = await fetchAllCommunities()
  return groupByCity(list)
}

module.exports = {
  loadCitiesCatalog,
  groupByCity,
  parseAddress
}
