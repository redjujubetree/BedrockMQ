<template>
  <div style="min-height: 100vh; background: #f0f2f5">
    <div class="header">
      <span class="logo">BedrockMQ Admin</span>
      <router-link
        v-for="item in navItems"
        :key="item.path"
        :to="item.path"
        custom
        v-slot="{ isActive, navigate }"
      >
        <span class="nav-item" :class="{ active: isActive }" @click="navigate">
          {{ item.label }}
        </span>
      </router-link>
    </div>
    <div class="main">
      <router-view />
    </div>
  </div>
</template>

<script setup>
const navItems = [
  { path: '/', label: 'Dashboard' },
  { path: '/messages', label: '消息管理' },
  { path: '/subscriptions', label: '订阅管理' },
]
</script>

<style>
* { box-sizing: border-box; margin: 0; padding: 0; }
body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; }
.header {
  height: 56px;
  background: #001529;
  display: flex;
  align-items: center;
  padding: 0 24px;
  box-shadow: 0 2px 8px rgba(0,0,0,0.2);
}
.logo {
  font-size: 18px;
  font-weight: bold;
  color: #fff;
  margin-right: 40px;
  white-space: nowrap;
}
.nav-item {
  cursor: pointer;
  padding: 0 16px;
  height: 56px;
  display: inline-flex;
  align-items: center;
  color: rgba(255,255,255,0.65);
  transition: all 0.2s;
  font-size: 14px;
}
.nav-item:hover { color: #fff; background: rgba(255,255,255,0.08); }
.nav-item.active { color: #fff; background: rgba(255,255,255,0.15); }
.main { padding: 24px; max-width: 1400px; margin: 0 auto; }
</style>
