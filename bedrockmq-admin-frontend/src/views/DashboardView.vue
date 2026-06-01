<template>
  <div>
    <el-row :gutter="16" style="margin-bottom: 24px">
      <el-col
        v-for="card in summaryCards"
        :key="card.label"
        :span="Math.floor(24 / summaryCards.length)"
      >
        <el-card shadow="hover" style="text-align: center; cursor: default">
          <div style="font-size: 38px; font-weight: bold; line-height: 1" :style="{ color: card.color }">
            {{ card.value }}
          </div>
          <div style="margin-top: 10px; color: #606266; font-size: 13px">{{ card.label }}</div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16">
      <el-col :span="17">
        <el-card>
          <template #header>
            <span>消息类型统计</span>
            <el-button style="float: right" size="small" @click="load">刷新</el-button>
          </template>
          <el-table :data="typeTable" border stripe v-loading="loading">
            <el-table-column prop="type" label="Topic:Consumer" min-width="180" />
            <el-table-column label="待处理" align="center" width="100">
              <template #default="{ row }">
                <el-tag v-if="row.pending > 0" type="info">{{ row.pending }}</el-tag>
                <span v-else style="color: #c0c4cc">—</span>
              </template>
            </el-table-column>
            <el-table-column label="处理中" align="center" width="100">
              <template #default="{ row }">
                <el-tag v-if="row.processing > 0" type="warning">{{ row.processing }}</el-tag>
                <span v-else style="color: #c0c4cc">—</span>
              </template>
            </el-table-column>
            <el-table-column label="已完成" align="center" width="100">
              <template #default="{ row }">
                <el-tag v-if="row.completed > 0" type="success">{{ row.completed }}</el-tag>
                <span v-else style="color: #c0c4cc">—</span>
              </template>
            </el-table-column>
            <el-table-column label="失败" align="center" width="100">
              <template #default="{ row }">
                <el-tag v-if="row.failed > 0" type="danger">{{ row.failed }}</el-tag>
                <span v-else style="color: #c0c4cc">—</span>
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-if="!loading && typeTable.length === 0" description="暂无数据" />
        </el-card>
      </el-col>

      <el-col :span="7">
        <el-card style="min-height: 200px">
          <template #header>已注册处理器</template>
          <div v-if="processors.length === 0" style="color: #999; text-align: center; padding: 24px 0">
            暂无注册处理器
          </div>
          <div v-else style="display: flex; flex-wrap: wrap; gap: 8px">
            <el-tag v-for="p in processors" :key="p" type="success">{{ p }}</el-tag>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { getStats, getProcessors } from '../api/index.js'

const loading = ref(false)
const statsItems = ref([])
const processors = ref([])

const PENDING = 0, PROCESSING = 1, COMPLETED = 2, FAILED = 3

const typeTable = computed(() => {
  const map = {}
  for (const item of statsItems.value) {
    const t = `${item.topic ?? '?'}:${item.consumer ?? '?'}`
    if (!map[t]) map[t] = { type: t, pending: 0, processing: 0, completed: 0, failed: 0 }
    const cnt = Number(item.count ?? 0)
    const s = Number(item.status)
    if (s === PENDING) map[t].pending += cnt
    else if (s === PROCESSING) map[t].processing += cnt
    else if (s === COMPLETED) map[t].completed += cnt
    else if (s === FAILED) map[t].failed += cnt
  }
  return Object.values(map)
})

const summaryCards = computed(() => {
  let total = 0, pending = 0, processing = 0, completed = 0, failed = 0
  for (const item of statsItems.value) {
    const cnt = Number(item.count ?? 0)
    const s = Number(item.status)
    total += cnt
    if (s === PENDING) pending += cnt
    else if (s === PROCESSING) processing += cnt
    else if (s === COMPLETED) completed += cnt
    else if (s === FAILED) failed += cnt
  }
  return [
    { label: '总消息数', value: total, color: '#303133' },
    { label: '待处理', value: pending, color: '#409eff' },
    { label: '处理中', value: processing, color: '#e6a23c' },
    { label: '已完成', value: completed, color: '#67c23a' },
    { label: '失败', value: failed, color: '#f56c6c' },
  ]
})

async function load() {
  loading.value = true
  try {
    const [s, p] = await Promise.all([getStats(), getProcessors()])
    statsItems.value = s.data ?? []
    processors.value = Array.from(p.data ?? [])
  } catch (e) {
    console.error('Failed to load dashboard data', e)
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>
