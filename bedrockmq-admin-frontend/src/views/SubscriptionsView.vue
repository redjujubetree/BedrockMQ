<template>
  <el-card>
    <template #header>
      <span>订阅管理</span>
      <el-button style="float: right" size="small" @click="load">刷新</el-button>
    </template>
    <el-table :data="subscriptions" border stripe v-loading="loading" style="width: 100%">
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="topic" label="Topic" min-width="140" />
      <el-table-column prop="consumer" label="Consumer" min-width="140" />
      <el-table-column prop="maxRetry" label="最大重试" width="100" align="center" />
      <el-table-column label="状态" width="100" align="center">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'danger'" size="small">
            {{ row.status === 1 ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="140" align="center">
        <template #default="{ row }">
          <el-button
            v-if="row.status === 1"
            size="small"
            type="danger"
            :loading="row._loading"
            @click="doDisable(row)"
          >停用</el-button>
          <el-button
            v-else
            size="small"
            type="success"
            :loading="row._loading"
            @click="doEnable(row)"
          >启用</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!loading && subscriptions.length === 0" description="暂无订阅" />
  </el-card>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { getSubscriptions, enableSubscription, disableSubscription } from '../api/index.js'

const loading = ref(false)
const subscriptions = ref([])

async function load() {
  loading.value = true
  try {
    const res = await getSubscriptions()
    subscriptions.value = (res.data ?? []).map(s => ({ ...s, _loading: false }))
  } catch {
    ElMessage.error('加载失败')
  } finally {
    loading.value = false
  }
}

async function doEnable(row) {
  row._loading = true
  try {
    await enableSubscription(row.id)
    row.status = 1
    ElMessage.success('已启用')
  } catch {
    ElMessage.error('操作失败')
  } finally {
    row._loading = false
  }
}

async function doDisable(row) {
  row._loading = true
  try {
    await disableSubscription(row.id)
    row.status = 0
    ElMessage.success('已停用')
  } catch {
    ElMessage.error('操作失败')
  } finally {
    row._loading = false
  }
}

onMounted(load)
</script>
