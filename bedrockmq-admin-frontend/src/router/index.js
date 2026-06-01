import { createRouter, createWebHashHistory } from 'vue-router'
import DashboardView from '../views/DashboardView.vue'
import MessagesView from '../views/MessagesView.vue'
import SubscriptionsView from '../views/SubscriptionsView.vue'

export default createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', component: DashboardView },
    { path: '/messages', component: MessagesView },
    { path: '/subscriptions', component: SubscriptionsView },
  ],
})
