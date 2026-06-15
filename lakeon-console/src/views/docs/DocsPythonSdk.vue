<template>
  <div class="ppage dp">
    <section class="ppage-section ppage-section-white">
      <div class="ppage-inner dp-inner">
        <router-link to="/docs" class="ppage-back">← {{ t('回到文档首页', 'Back to docs') }}</router-link>
        <div class="ppage-eyebrow">{{ t('参考文档 · Python SDK', 'Reference · Python SDK') }}</div>
        <h1 class="dp-title">Python SDK</h1>
        <p class="dp-lede">{{ t('Python SDK 负责调用 Lakebase core API。', 'The Python SDK calls Lakebase core APIs.') }}</p>
      </div>
    </section>

    <section class="ppage-section">
      <div class="ppage-inner dp-inner">
        <h2 class="ppage-section-title">{{ t('快速开始', 'Quick start') }}</h2>
        <pre class="code-block"><code>from dbay import DBay

client = DBay(api_key="dbay_sk_your_key_here")

db = client.databases.create(name="app", compute_size="1cu")
print(db.id, db.connection_uri)

branch = client.branches.create(database_id=db.id, name="experiment")
print(branch.id)</code></pre>
        <p class="dp-note">
          {{ t('高层数据智能 SDK 会在 dbay-agent 仓库中维护。', 'Higher-level data intelligence SDKs are maintained in the dbay-agent repository.') }}
        </p>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { useLocale } from '../../stores/locale'

const { t } = useLocale()
</script>

<style scoped>
.dp { background: var(--c-bg-alt); }
.dp-inner { max-width: 1040px; }
.dp-title {
  font-family: var(--font-display);
  font-weight: 500;
  font-size: clamp(40px, 5vw, 72px);
  line-height: 1.05;
  color: var(--c-primary);
  margin: 0 0 var(--space-lg);
}
.dp-lede,
.dp-note {
  font-size: 18px;
  line-height: 1.8;
  color: var(--c-text-muted);
}
.code-block {
  margin: var(--space-md) 0 0;
  padding: var(--space-md);
  border: 1px solid var(--c-border);
  border-radius: 8px;
  overflow-x: auto;
  background: #fff;
  font-family: var(--font-mono);
}
</style>
