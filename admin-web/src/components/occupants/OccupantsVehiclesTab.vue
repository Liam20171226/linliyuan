<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../../api'
import { authFailRedirectPath } from '../../authSession'
import { flattenRoomsFromTree } from '../../residentRoles'

const router = useRouter()
const loading = ref(false)
const saving = ref(false)
const list = ref<any[]>([])
const total = ref(0)
const page = ref(1)

const plateQ = ref('')
const roomIdQ = ref<number | ''>('')
const boundQ = ref('')
const roomOptions = ref<{ id: number; label: string }[]>([])

const dialogVisible = ref(false)
const editing = ref<any | null>(null)
const form = ref({
  roomId: null as number | null,
  plateNo: '',
  parkingSpaceId: null as number | null | '',
})
const parkingOptions = ref<{ id: number; spaceNo: string }[]>([])

const dialogTitle = computed(() => (editing.value ? '修改车辆' : '新增车辆'))

async function loadRooms() {
  try {
    const { data } = await api.get('/staff/space-tree')
    if (data.code === 0) {
      // 与名单/空间页一致：树在 data.buildings，不是 data 本身
      roomOptions.value = flattenRoomsFromTree(data.data?.buildings || [])
    } else {
      roomOptions.value = []
      ElMessage.error(data.message || '加载房屋失败')
    }
  } catch {
    roomOptions.value = []
  }
}

async function load() {
  loading.value = true
  try {
    const { data } = await api.get('/staff/vehicles', {
      params: {
        page: page.value,
        pageSize: 20,
        plateNo: plateQ.value.trim() || undefined,
        roomId: roomIdQ.value === '' ? undefined : roomIdQ.value,
        bound: boundQ.value || undefined,
      },
    })
    if (data.code === 0) {
      list.value = data.data.list || []
      total.value = data.data.total || 0
    } else {
      ElMessage.error(data.message || '加载失败')
      if (data.code === 40301 || data.code === 40101) router.push(authFailRedirectPath())
    }
  } finally {
    loading.value = false
  }
}

function resetSearch() {
  plateQ.value = ''
  roomIdQ.value = ''
  boundQ.value = ''
  page.value = 1
  load()
}

async function loadParkingOptions(roomId: number, keepSpaceId?: number | null) {
  const { data } = await api.get(`/staff/rooms/${roomId}/parking-options`, {
    params: {
      availableOnly: true,
      keepSpaceId: keepSpaceId || undefined,
    },
  })
  if (data.code === 0) {
    parkingOptions.value = (data.data || []).map((s: any) => ({
      id: s.id,
      spaceNo: s.spaceNo,
    }))
  } else {
    parkingOptions.value = []
  }
}

watch(
  () => form.value.roomId,
  async (rid) => {
    if (!rid || editing.value) return
    form.value.parkingSpaceId = ''
    await loadParkingOptions(rid)
  },
)

function openAdd() {
  editing.value = null
  form.value = { roomId: null, plateNo: '', parkingSpaceId: '' }
  parkingOptions.value = []
  dialogVisible.value = true
}

async function openEdit(row: any) {
  editing.value = row
  form.value = {
    roomId: row.roomId,
    plateNo: row.plateNo || '',
    parkingSpaceId: row.parkingSpaceId ?? '',
  }
  await loadParkingOptions(row.roomId, row.parkingSpaceId)
  dialogVisible.value = true
}

function normalizePlate(p: string) {
  return p.trim().toUpperCase().replace(/\s+/g, '')
}

async function save() {
  const plate = normalizePlate(form.value.plateNo)
  if (!plate) {
    ElMessage.warning('请填写车牌号')
    return
  }
  if (!editing.value && !form.value.roomId) {
    ElMessage.warning('请选择关联房屋')
    return
  }
  saving.value = true
  try {
    if (editing.value) {
      const body: Record<string, unknown> = {
        action: 'UPDATE',
        roomId: editing.value.roomId,
        vehicleId: editing.value.id,
        plateNo: plate,
        parkingSpaceId:
          form.value.parkingSpaceId === '' || form.value.parkingSpaceId == null
            ? null
            : form.value.parkingSpaceId,
      }
      const { data } = await api.post('/staff/fixes/vehicles', body)
      if (data.code !== 0) {
        ElMessage.error(data.message)
        return
      }
      ElMessage.success('已保存')
    } else {
      const body: Record<string, unknown> = {
        action: 'ADD',
        roomId: form.value.roomId,
        plateNo: plate,
      }
      if (form.value.parkingSpaceId !== '' && form.value.parkingSpaceId != null) {
        body.parkingSpaceId = form.value.parkingSpaceId
      }
      const { data } = await api.post('/staff/fixes/vehicles', body)
      if (data.code !== 0) {
        ElMessage.error(data.message)
        return
      }
      ElMessage.success(
        form.value.parkingSpaceId ? '已新增' : '已新增（未选手动车位时将自动补绑空位）',
      )
    }
    dialogVisible.value = false
    load()
  } finally {
    saving.value = false
  }
}

async function remove(row: any) {
  try {
    await ElMessageBox.confirm(
      `确认删除车辆「${row.plateNo}」？删除后本房空位将按规则自动补绑。`,
      '删除车辆',
      { type: 'warning' },
    )
  } catch {
    return
  }
  const { data } = await api.post('/staff/fixes/vehicles', {
    action: 'DELETE',
    roomId: row.roomId,
    vehicleId: row.id,
  })
  if (data.code === 0) {
    ElMessage.success('已删除')
    load()
  } else ElMessage.error(data.message)
}

onMounted(async () => {
  await loadRooms()
  load()
})
</script>

<template>
  <div v-loading="loading">
    <div class="toolbar">
      <el-input
        v-model="plateQ"
        placeholder="车牌号"
        clearable
        style="width:140px"
        @keyup.enter="page=1;load()"
      />
      <el-select
        v-model="roomIdQ"
        placeholder="全部房屋"
        clearable
        filterable
        style="width:220px"
        @change="page=1;load()"
      >
        <el-option v-for="r in roomOptions" :key="r.id" :label="r.label" :value="r.id" />
      </el-select>
      <el-select v-model="boundQ" placeholder="绑位状态" clearable style="width:120px" @change="page=1;load()">
        <el-option label="已绑车位" value="yes" />
        <el-option label="未绑车位" value="no" />
      </el-select>
      <el-button type="primary" @click="page=1;load()">查询</el-button>
      <el-button @click="resetSearch">重置</el-button>
      <el-button type="primary" @click="openAdd">新增车辆</el-button>
    </div>

    <el-table :data="list" style="width:100%; margin-top:12px">
      <el-table-column type="index" label="序号" width="60" :index="(i: number) => (page - 1) * 20 + i + 1" />
      <el-table-column prop="plateNo" label="车牌号" width="120" />
      <el-table-column label="关联房屋" min-width="180">
        <template #default="{ row }">{{ row.address || row.roomNo || '—' }}</template>
      </el-table-column>
      <el-table-column label="绑定车位" width="120">
        <template #default="{ row }">{{ row.parkingSpaceNo || '未绑' }}</template>
      </el-table-column>
      <el-table-column prop="createdAt" label="登记时间" width="170" />
      <el-table-column label="操作" width="140" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">修改</el-button>
          <el-button link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager">
      <el-pagination
        background
        layout="prev, pager, next, total"
        :total="total"
        :page-size="20"
        v-model:current-page="page"
        @current-change="load"
      />
    </div>
    <p class="hint">
      同小区车牌唯一。不选车位时若本房有空闲挂靠车位将自动补绑。不可改房屋，需换房请删除后重新登记。
    </p>

    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="480px">
      <el-form label-position="top">
        <el-form-item label="关联房屋" required>
          <el-select
            v-model="form.roomId"
            placeholder="选择房屋"
            filterable
            style="width:100%"
            :disabled="!!editing"
          >
            <el-option v-for="r in roomOptions" :key="r.id" :label="r.label" :value="r.id" />
          </el-select>
          <p v-if="editing" class="hint">房屋不可修改，换房请删除后重建。</p>
        </el-form-item>
        <el-form-item label="车牌号" required>
          <el-input v-model="form.plateNo" placeholder="如 粤B12345" maxlength="32" />
        </el-form-item>
        <el-form-item label="绑定车位（可选）">
          <el-select
            v-model="form.parkingSpaceId"
            placeholder="不选则自动补绑空位 / 可保持未绑"
            clearable
            style="width:100%"
            :disabled="!form.roomId"
          >
            <el-option
              v-for="s in parkingOptions"
              :key="s.id"
              :label="s.spaceNo"
              :value="s.id"
            />
          </el-select>
          <p class="hint">仅显示本房已挂靠且空闲的车位。清空表示未绑车位。</p>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.toolbar { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
.pager { margin-top: 12px; display: flex; justify-content: flex-end; }
.hint { margin: 10px 0 0; color: #778; font-size: 13px; }
</style>
