<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../api'
import StaffNav from '../components/StaffNav.vue'
import StaffSessionAction from '../components/StaffSessionAction.vue'
import OccupantsListTab from '../components/occupants/OccupantsListTab.vue'
import OccupantsImportTab from '../components/occupants/OccupantsImportTab.vue'
import OccupantsAuthTab from '../components/occupants/OccupantsAuthTab.vue'
import OccupantsChangesTab from '../components/occupants/OccupantsChangesTab.vue'
import OccupantsVehiclesTab from '../components/occupants/OccupantsVehiclesTab.vue'

/** 业委会不使用物业 Web；车辆 Tab 仅物业/平台可见（本后台无业委会登录入口）。 */
const TAB_KEYS = ['list', 'import', 'auth', 'changes', 'vehicles'] as const
type TabKey = (typeof TAB_KEYS)[number]

const route = useRoute()
const router = useRouter()
const authPending = ref(0)
const roomChangePending = ref(0)

const activeTab = computed<TabKey>(() => {
  const t = route.query.tab
  if (typeof t === 'string' && (TAB_KEYS as readonly string[]).includes(t)) return t as TabKey
  return 'list'
})

function onTabChange(name: string | number) {
  const tab = String(name)
  if (tab === activeTab.value) return
  router.replace({ path: '/occupants', query: tab === 'list' ? {} : { tab } })
}

function tabLabel(base: string, n: number) {
  if (!n) return base
  return `${base} (${n > 99 ? '99+' : n})`
}

async function loadBadges() {
  try {
    const { data } = await api.get('/staff/nav-badges')
    if (data.code === 0 && data.data) {
      authPending.value = Number(data.data.authPending || 0)
      roomChangePending.value = Number(data.data.roomChangePending || 0)
    }
  } catch {
    /* ignore */
  }
}

watch(
  () => route.query.tab,
  (t) => {
    if (t != null && typeof t === 'string' && !(TAB_KEYS as readonly string[]).includes(t)) {
      router.replace({ path: '/occupants' })
    }
    loadBadges()
  },
  { immediate: true },
)

onMounted(loadBadges)
</script>

<template>
  <div class="page">
    <header>
      <div>
        <h2>住户</h2>
        <p class="sub">名单、导入、认证/变更审核、车辆</p>
        <StaffNav />
      </div>
      <StaffSessionAction />
    </header>

    <el-tabs :model-value="activeTab" class="tabs" @tab-change="onTabChange">
      <el-tab-pane label="名单" name="list">
        <OccupantsListTab v-if="activeTab === 'list'" />
      </el-tab-pane>
      <el-tab-pane label="导入" name="import">
        <OccupantsImportTab v-if="activeTab === 'import'" />
      </el-tab-pane>
      <el-tab-pane :label="tabLabel('认证审核', authPending)" name="auth">
        <OccupantsAuthTab v-if="activeTab === 'auth'" />
      </el-tab-pane>
      <el-tab-pane :label="tabLabel('变更审核', roomChangePending)" name="changes">
        <OccupantsChangesTab v-if="activeTab === 'changes'" />
      </el-tab-pane>
      <el-tab-pane label="车辆" name="vehicles">
        <OccupantsVehiclesTab v-if="activeTab === 'vehicles'" />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.page { padding: 24px 32px; max-width: 1500px; margin: 0 auto; }
header { display: flex; justify-content: space-between; gap: 16px; align-items: flex-start; }
h2 { margin: 0; color: #1f4e3d; }
.sub { margin: 4px 0 0; color: #667; font-size: 14px; }
.tabs { margin-top: 16px; }
</style>
