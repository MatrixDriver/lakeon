<template>
  <div class="ext-callback-page">
    <div class="ext-callback-card">
      <div class="brand-logo">DBay</div>
      <div class="success-msg">登录成功，正在返回扩展...</div>
      <div class="hint">如果页面没有自动关闭，请手动关闭此标签页。</div>
    </div>
  </div>
</template>

<script setup lang="ts">
// This page exists as a web-accessible callback target.
// The Chrome extension's background.js detects navigation to this URL
// and extracts the API key from the hash fragment.
// No logic needed here — the extension handles everything.
</script>

<style scoped>
.ext-callback-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #faf8f5;
  padding: 24px;
}

.ext-callback-card {
  text-align: center;
  max-width: 340px;
  background: #fff;
  border-radius: 16px;
  padding: 40px 32px;
  box-shadow: 0 4px 24px rgba(140, 100, 50, 0.10), 0 1px 4px rgba(140, 100, 50, 0.06);
  border: 1px solid rgba(193, 154, 107, 0.15);
}

.brand-logo {
  font-size: 28px;
  font-weight: 800;
  color: #c19a6b;
  letter-spacing: 1px;
  margin-bottom: 18px;
}

.success-msg {
  font-size: 16px;
  font-weight: 600;
  color: #3a2e1e;
  margin-bottom: 12px;
}

.hint {
  font-size: 13px;
  color: #9c8a72;
}
</style>
