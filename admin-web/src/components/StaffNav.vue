<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { api } from '../api'
import { communityLabel, isPlatformRole } from '../authSession'

const router = useRouter()
const route = useRoute()
const platform = computed(() => isPlatformRole())
const title = computed(() => communityLabel())

const badges = ref<Record<string, number>>({})
let timer: ReturnType<typeof setInterval> | null = null

const links = [
  { path: '/space', label: '空间', badgeKey: '' },
  { path: '/occupants', label: '住户', badgeKey: 'occupants' },
  { path: '/team', label: '岗位人员', badgeKey: '' },
  { path: '/billing', label: '账单', badgeKey: '' },
  { path: '/finance', label: '财务', badgeKey: 'finance' },
  { path: '/notices', label: '公告', badgeKey: '' },
  { path: '/service-desk', label: '报事报修', badgeKey: 'serviceDesk' },
  { path: '/inspection', label: '巡检', badgeKey: '' },
  { path: '/votes', label: '投票', badgeKey: '' },
]

function badgeOf(key: string) {
  if (!key) return 0
  const n = Number(badges.value[key] || 0)
  return Number.isFinite(n) && n > 0 ? Math.floor(n) : 0
}

function badgeText(n: number) {
  return n > 99 ? '99+' : String(n)
}

function go(path: string) {
  if (route.path !== path) router.push(path)
}

async function loadBadges() {
  try {
    const { data } = await api.get('/staff/nav-badges')
    if (data.code === 0 && data.data) {
      badges.value = data.data
    }
  } catch {
    /* ignore */
  }
}

onMounted(() => {
  loadBadges()
  timer = setInterval(loadBadges, 30000)
})
onUnmounted(() => {
  if (timer) clearInterval(timer)
})
watch(
  () => route.path,
  () => { loadBadges() },
)
</script>

<template>
  <nav class="staff-nav">
    <span v-if="platform && title" class="ctx">平台 · {{ title }}</span>
    <span v-else-if="title" class="ctx">物业 · {{ title }}</span>
    <button
      v-for="l in links"
      :key="l.path"
      type="button"
      class="link"
      :class="{ active: route.path === l.path || (l.path === '/occupants' && ['/import', '/auth-review', '/room-changes'].includes(route.path)) }"
      @click="go(l.path)"
    >
      <span class="link-text">{{ l.label }}</span>
      <span v-if="badgeOf(l.badgeKey)" class="dot">{{ badgeText(badgeOf(l.badgeKey)) }}</span>
    </button>
  </nav>
</template>

<style scoped>
.staff-nav {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 10px;
  align-items: center;
}
.ctx {
  flex: 0 0 auto;
  height: 32px;
  box-sizing: border-box;
  display: inline-flex;
  align-items: center;
  font-size: 12px;
  color: #1f4e3d;
  background: #e7efe9;
  padding: 0 8px;
  margin-right: 4px;
  line-height: 1;
}
.link {
  position: relative;
  box-sizing: border-box;
  /* 统一尺寸，避免「报事报修」等长文案撑开导致页签与右侧按钮漂移 */
  flex: 0 0 92px;
  width: 92px;
  height: 32px;
  padding: 0 8px;
  border: 1px solid #c5d4cc;
  background: #f7faf8;
  color: #2a4a3c;
  font-size: 13px;
  font-weight: 500;
  line-height: 1;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  text-align: center;
  cursor: pointer;
}
.link.active {
  background: #1f4e3d;
  border-color: #1f4e3d;
  color: #fff;
}
.link-text {
  position: relative;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 100%;
}
.dot {
  position: absolute;
  top: -6px;
  right: -6px;
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  border-radius: 999px;
  background: #e24c4c;
  color: #fff;
  font-size: 11px;
  font-weight: 700;
  line-height: 18px;
  text-align: center;
  box-sizing: border-box;
  border: 1px solid #fff;
}
</style>
