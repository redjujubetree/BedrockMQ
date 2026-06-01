<template>
  <div>
    <!-- Filter bar -->
    <el-card style="margin-bottom: 16px">
      <el-form :inline="true" style="display: flex; flex-wrap: wrap; align-items: center">
        <el-form-item label="Topic">
          <el-select
            v-model="filter.topic"
            placeholder="全部"
            clearable
            filterable
            style="width: 160px"
            @change="onTopicChange"
          >
            <el-option v-for="t in topicOptions" :key="t" :label="t" :value="t" />
          </el-select>
        </el-form-item>
        <el-form-item label="Consumer">
          <el-select
            v-model="filter.consumer"
            placeholder="全部"
            clearable
            filterable
            style="width: 160px"
          >
            <el-option v-for="c in consumerOptions" :key="c" :label="c" :value="c" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="filter.status" placeholder="全部" clearable style="width: 120px">
            <el-option label="待处理" :value="0" />
            <el-option label="处理中" :value="1" />
            <el-option label="已完成" :value="2" />
            <el-option label="失败" :value="3" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="search">查询</el-button>
          <el-button @click="reset">重置</el-button>
        </el-form-item>
        <el-form-item style="margin-left: auto; margin-right: 0">
          <el-button type="success" @click="openSend">
            <el-icon><Plus /></el-icon>&nbsp;发送消息
          </el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- Messages table -->
    <el-card>
      <!-- Batch action bar -->
      <div v-if="selection.length > 0" style="margin-bottom: 12px; display: flex; align-items: center; gap: 12px">
        <span style="color: #606266">已选 {{ selection.length }} 条</span>
        <el-button type="warning" plain size="small" :loading="batchRetryLoading" @click="doBatchRetry">批量重试</el-button>
        <el-button type="primary" plain size="small" @click="openBatchMaxRetry">批量修改重试次数</el-button>
        <el-button size="small" @click="clearSelection">取消选择</el-button>
      </div>

      <el-table :data="messages" border stripe v-loading="loading" style="width: 100%"
                @selection-change="onSelectionChange" ref="tableRef">
        <el-table-column type="selection" width="46" />
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="topic" label="Topic" min-width="110" show-overflow-tooltip />
        <el-table-column prop="consumer" label="Consumer" min-width="110" show-overflow-tooltip />
        <el-table-column prop="messageSource" label="来源" min-width="110" show-overflow-tooltip />
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="STATUS_MAP[row.status]?.tagType ?? ''" size="small">
              {{ STATUS_MAP[row.status]?.label ?? row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="重试" width="75" align="center">
          <template #default="{ row }">{{ row.retryCount }}/{{ row.maxRetry }}</template>
        </el-table-column>
        <el-table-column prop="nodeId" label="处理节点" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ row.nodeId || '—' }}</template>
        </el-table-column>
        <el-table-column label="创建时间" min-width="155">
          <template #default="{ row }">{{ fmtDt(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="计划时间" min-width="155">
          <template #default="{ row }">{{ fmtDt(row.scheduledAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="270" align="center" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="viewMsg(row)">查看</el-button>
            <el-button size="small" type="warning" v-if="row.status === 3" @click="doRetry(row.id)">重试</el-button>
            <el-button size="small" v-if="row.status === 0" @click="doCancel(row.id)">取消</el-button>
<el-button size="small" type="danger" @click="doDelete(row.id)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        style="margin-top: 16px; justify-content: flex-end; display: flex"
        :current-page="page"
        :page-size="pageSize"
        :total="total"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next, jumper"
        @current-change="p => { page = p; load() }"
        @size-change="s => { pageSize = s; page = 1; load() }"
      />
    </el-card>

    <!-- Detail dialog -->
    <el-dialog v-model="detailVisible" title="消息详情" width="680px">
      <div v-if="detailLoading" v-loading="true" style="height: 120px" />
      <el-descriptions v-else-if="detail" :column="2" border>
        <el-descriptions-item label="ID">
          <span class="copyable" @dblclick="copyValue(detail.id)" title="双击复制">{{ detail.id }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="Topic">
          <span class="copyable" @dblclick="copyValue(detail.topic)" title="双击复制">{{ detail.topic }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="Consumer">
          <span class="copyable" @dblclick="copyValue(detail.consumer)" title="双击复制">{{ detail.consumer }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="来源">
          <span class="copyable" @dblclick="copyValue(detail.messageSource)" title="双击复制">{{ detail.messageSource }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="STATUS_MAP[detail.status]?.tagType ?? ''">
            {{ STATUS_MAP[detail.status]?.label ?? detail.status }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="重试次数">{{ detail.retryCount }} / {{ detail.maxRetry }}</el-descriptions-item>
        <el-descriptions-item label="处理节点">
          <span v-if="detail.nodeId" class="copyable" @dblclick="copyValue(detail.nodeId)" title="双击复制">{{ detail.nodeId }}</span>
          <span v-else>—</span>
        </el-descriptions-item>
        <el-descriptions-item label="创建时间">{{ fmtDt(detail.createdAt) }}</el-descriptions-item>
        <el-descriptions-item label="更新时间">{{ fmtDt(detail.updatedAt) }}</el-descriptions-item>
        <el-descriptions-item label="计划时间" :span="2">{{ fmtDt(detail.scheduledAt) }}</el-descriptions-item>
        <el-descriptions-item v-if="detail.errorMsg" label="错误信息" :span="2">
          <span style="color: #f56c6c">{{ detail.errorMsg }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="消息体" :span="2">
          <pre
            style="margin:0; white-space:pre-wrap; word-break:break-all; font-size:12px; max-height:280px; overflow:auto; background:#f5f7fa; padding:8px; border-radius:4px; cursor:pointer; user-select:text"
            title="双击复制"
            @dblclick="copyPayload"
          >{{ fmtPayload(detail.payload) }}</pre>
        </el-descriptions-item>
      </el-descriptions>
    </el-dialog>

    <!-- Batch update max retry dialog -->
    <el-dialog v-model="batchMaxRetryVisible" title="批量修改最大重试次数" width="360px">
      <el-form label-width="90px">
        <el-form-item label="选中记录">{{ selection.length }} 条</el-form-item>
        <el-form-item label="新上限">
          <el-input-number v-model="batchNewMaxRetry" :min="0" :max="100" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="batchMaxRetryVisible = false">取消</el-button>
        <el-button type="primary" :loading="batchMaxRetryLoading" @click="doBatchUpdateMaxRetry">保存</el-button>
      </template>
    </el-dialog>

    <!-- Send message dialog -->
    <el-dialog v-model="sendVisible" title="发送消息" width="560px" @closed="clearForm">
      <el-form :model="form" ref="formRef" label-width="90px" @submit.prevent>
        <el-form-item
          label="Topic"
          prop="topic"
          :rules="[{ required: true, message: '请输入 topic', trigger: 'blur' }]"
        >
          <el-input v-model="form.topic" placeholder="e.g. order" />
        </el-form-item>
        <el-form-item
          label="来源"
          prop="messageSource"
          :rules="[{ required: true, message: '请输入消息来源', trigger: 'blur' }]"
        >
          <el-input v-model="form.messageSource" placeholder="发送方服务名" />
        </el-form-item>
        <el-form-item
          label="消息体"
          prop="payload"
          :rules="[{ required: true, message: '请输入消息体', trigger: 'blur' }]"
        >
          <el-input
            v-model="form.payload"
            type="textarea"
            :rows="6"
            placeholder='{"key": "value"}'
            style="font-family: monospace"
          />
        </el-form-item>
        <el-form-item label="最大重试">
          <el-input-number v-model="form.maxRetry" :min="0" :max="20" />
        </el-form-item>
        <el-form-item label="计划时间">
          <el-date-picker
            v-model="form.scheduledAt"
            type="datetime"
            placeholder="留空则立即投递"
            style="width: 100%"
            value-format="YYYY-MM-DDTHH:mm:ss"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="sendVisible = false">取消</el-button>
        <el-button type="primary" :loading="sending" @click="doSend">发送</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getMessages, getMessage, retryMessage, cancelMessage, deleteMessage, sendMessage, batchRetryMessages, batchUpdateMaxRetry, getSubscriptions } from '../api/index.js'

const STATUS_MAP = {
  0: { label: '待处理', tagType: 'info' },
  1: { label: '处理中', tagType: 'warning' },
  2: { label: '已完成', tagType: 'success' },
  3: { label: '失败', tagType: 'danger' },
}

const loading = ref(false)
const messages = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
const filter = reactive({ topic: '', consumer: '', status: null })

const subscriptions = ref([])
const topicOptions = computed(() => [...new Set(subscriptions.value.map(s => s.topic))].sort())
const consumerOptions = computed(() => {
  const subs = filter.topic
    ? subscriptions.value.filter(s => s.topic === filter.topic)
    : subscriptions.value
  return [...new Set(subs.map(s => s.consumer))].sort()
})

const detailVisible = ref(false)
const detail = ref(null)
const detailLoading = ref(false)

const tableRef = ref(null)
const selection = ref([])

const batchRetryLoading = ref(false)

const batchMaxRetryVisible = ref(false)
const batchMaxRetryLoading = ref(false)
const batchNewMaxRetry = ref(0)

const sendVisible = ref(false)
const sending = ref(false)
const formRef = ref(null)
const form = reactive({ topic: '', messageSource: '', payload: '', maxRetry: 3, scheduledAt: null })

function fmtDt(val) {
  if (!val) return '—'
  if (Array.isArray(val)) {
    const [y, mo, d, h = 0, mi = 0, s = 0] = val
    return `${y}-${pad(mo)}-${pad(d)} ${pad(h)}:${pad(mi)}:${pad(s)}`
  }
  return String(val).replace('T', ' ').slice(0, 19)
}

function pad(n) { return String(n).padStart(2, '0') }

function fmtPayload(p) {
  if (!p) return ''
  try { return JSON.stringify(JSON.parse(p), null, 2) } catch { return p }
}

async function copyValue(val) {
  if (!val) return
  try {
    await navigator.clipboard.writeText(String(val))
    ElMessage.success('已复制')
  } catch {
    ElMessage.error('复制失败，请手动选择')
  }
}

async function copyPayload() {
  const text = fmtPayload(detail.value?.payload)
  if (!text) return
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('已复制')
  } catch {
    ElMessage.error('复制失败，请手动选择')
  }
}

async function load() {
  loading.value = true
  try {
    const params = { page: page.value, size: pageSize.value }
    if (filter.topic) params.topic = filter.topic
    if (filter.consumer) params.consumer = filter.consumer
    if (filter.status !== null && filter.status !== undefined && filter.status !== '') {
      params.status = filter.status
    }
    const res = await getMessages(params)
    messages.value = res.data.records ?? []
    total.value = Number(res.data.total ?? 0)
  } catch (e) {
    ElMessage.error('加载失败')
  } finally {
    loading.value = false
  }
}

function onTopicChange() {
  if (filter.consumer && !consumerOptions.value.includes(filter.consumer)) {
    filter.consumer = ''
  }
  search()
}

function search() { page.value = 1; load() }

function reset() {
  filter.topic = ''
  filter.consumer = ''
  filter.status = null
  page.value = 1
  load()
}

async function viewMsg(row) {
  detail.value = null
  detailVisible.value = true
  detailLoading.value = true
  try {
    const res = await getMessage(row.id)
    detail.value = res.data
  } catch {
    ElMessage.error('加载详情失败')
    detailVisible.value = false
  } finally {
    detailLoading.value = false
  }
}

async function doRetry(id) {
  try {
    await retryMessage(id)
    ElMessage.success('已重新投递')
    load()
  } catch { ElMessage.error('操作失败') }
}

async function doCancel(id) {
  try {
    await ElMessageBox.confirm('确认取消该消息？取消后状态将置为失败。', '提示', { type: 'warning' })
    await cancelMessage(id)
    ElMessage.success('已取消')
    load()
  } catch (e) {
    if (e !== 'cancel') ElMessage.error('操作失败')
  }
}

async function doDelete(id) {
  try {
    await ElMessageBox.confirm('确认删除该消息记录？', '警告', { type: 'warning' })
    await deleteMessage(id)
    ElMessage.success('已删除')
    load()
  } catch (e) {
    if (e !== 'cancel') ElMessage.error('操作失败')
  }
}

function onSelectionChange(rows) { selection.value = rows }
function clearSelection() { tableRef.value?.clearSelection() }

async function doBatchRetry() {
  const failedIds = selection.value.filter(r => r.status === 3).map(r => r.id)
  if (failedIds.length === 0) {
    ElMessage.warning('选中记录中没有失败状态的消息')
    return
  }
  batchRetryLoading.value = true
  try {
    const res = await batchRetryMessages(failedIds)
    ElMessage.success(`已重新投递 ${res.data.updated} 条`)
    clearSelection()
    load()
  } catch {
    ElMessage.error('操作失败')
  } finally {
    batchRetryLoading.value = false
  }
}

function openBatchMaxRetry() {
  batchNewMaxRetry.value = 0
  batchMaxRetryVisible.value = true
}

async function doBatchUpdateMaxRetry() {
  batchMaxRetryLoading.value = true
  try {
    const ids = selection.value.map(r => r.id)
    const res = await batchUpdateMaxRetry(ids, batchNewMaxRetry.value)
    ElMessage.success(`已更新 ${res.data.updated} 条`)
    batchMaxRetryVisible.value = false
    clearSelection()
    load()
  } catch {
    ElMessage.error('操作失败')
  } finally {
    batchMaxRetryLoading.value = false
  }
}

function openSend() { sendVisible.value = true }

function clearForm() {
  Object.assign(form, { topic: '', messageSource: '', payload: '', maxRetry: 3, scheduledAt: null })
  formRef.value?.resetFields()
}

async function doSend() {
  try { await formRef.value.validate() } catch { return }
  sending.value = true
  try {
    const body = {
      topic: form.topic,
      messageSource: form.messageSource,
      payload: form.payload,
      maxRetry: form.maxRetry,
    }
    if (form.scheduledAt) body.scheduledAt = form.scheduledAt
    await sendMessage(body)
    ElMessage.success('消息发送成功')
    sendVisible.value = false
    load()
  } catch (e) {
    ElMessage.error(e?.response?.data?.error ?? '发送失败')
  } finally {
    sending.value = false
  }
}

onMounted(async () => {
  try {
    const res = await getSubscriptions()
    subscriptions.value = res.data ?? []
  } catch { /* non-fatal, selects fall back to empty */ }
  load()
})
</script>

<style scoped>
.copyable {
  cursor: pointer;
  user-select: text;
}
.copyable:hover {
  text-decoration: underline dotted;
}
</style>
