import axios from 'axios'

const api = axios.create({ baseURL: '/bedrockmq-admin/bedrock' })

export const getStats = () => api.get('/stats')
export const getProcessors = () => api.get('/processors')
export const getMessages = (params) => api.get('/messages', { params })
export const getMessage = (id) => api.get(`/messages/${id}`)
export const retryMessage = (id) => api.post(`/messages/${id}/retry`)
export const cancelMessage = (id) => api.post(`/messages/${id}/cancel`)
export const updateMaxRetry = (id, maxRetry) => api.post(`/messages/${id}/max-retry`, { maxRetry })
export const batchRetryMessages = (ids) => api.post('/messages/batch/retry', { ids })
export const batchUpdateMaxRetry = (ids, maxRetry) => api.post('/messages/batch/max-retry', { ids, maxRetry })
export const deleteMessage = (id) => api.post(`/messages/${id}/delete`)
export const sendMessage = (data) => api.post('/messages', data)
export const getSubscriptions = () => api.get('/subscriptions')
export const enableSubscription = (id) => api.post(`/subscriptions/${id}/enable`)
export const disableSubscription = (id) => api.post(`/subscriptions/${id}/disable`)
export const updateSubscriptionMaxRetry = (id, maxRetry) => api.post(`/subscriptions/${id}/max-retry`, { maxRetry })
