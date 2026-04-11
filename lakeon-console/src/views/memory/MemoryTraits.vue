<template>
  <div class="page-container">
    <div class="page-header">
      <h1 class="page-title">反思洞察</h1>
      <button v-if="baseId"
              :disabled="digesting"
              @click="runDigest"
              class="btn btn-primary"
              :style="digesting ? 'opacity: 0.6; cursor: not-allowed;' : ''">
        {{ digesting ? '反思中...' : '执行反思' }}
      </button>
    </div>

    <MemoryBaseSelector @change="onBaseChange" />

    <div v-if="baseId" style="margin-top: 20px;">
      <p v-if="loading" style="text-align: center; color: #999; padding: 40px 0;">加载中...</p>
      <p v-else-if="traits.length === 0" style="text-align: center; color: #999; padding: 40px 0;">
        暂无洞察。请点击"执行反思"按钮生成洞察。
      </p>

      <template v-else>
        <!-- Stage pipeline header -->
        <div style="display: flex; align-items: center; margin-bottom: 4px;">
          <template v-for="(stage, i) in allStages" :key="stage">
            <div style="flex: 1; text-align: center;">
              <div style="display: inline-flex; align-items: center; gap: 6px;">
                <span style="font-size: 14px; font-weight: 600;"
                      :style="`color: ${TRAIT_STAGE_COLORS[stage]?.text};`">
                  {{ TRAIT_STAGE_LABELS[stage] }}
                </span>
                <span v-if="groupByStage(stage).length > 0"
                      style="font-size: 11px; padding: 0 6px; border-radius: 10px;"
                      :style="`background: ${TRAIT_STAGE_COLORS[stage]?.bg}; color: ${TRAIT_STAGE_COLORS[stage]?.text};`">
                  {{ groupByStage(stage).length }}
                </span>
              </div>
            </div>
            <div v-if="i < allStages.length - 1"
                 style="width: 24px; flex-shrink: 0; text-align: center; color: #d9d9d9; font-size: 12px;">
              →
            </div>
          </template>
        </div>

        <!-- Stage columns -->
        <div style="display: flex; gap: 0; align-items: flex-start;">
          <template v-for="(stage, i) in allStages" :key="stage">
            <div style="flex: 1; min-width: 0; padding: 0 6px;">
              <div style="display: flex; flex-direction: column; gap: 8px;">
                <div v-for="trait in groupByStage(stage)" :key="trait.id"
                     class="card" style="padding: 12px; border: 1px solid #e8e4df; border-radius: 6px; background: #fff;">
                  <!-- Subtype -->
                  <div v-if="trait.trait_subtype" style="font-size: 11px; color: #999; margin-bottom: 4px;">
                    {{ trait.trait_subtype }}
                  </div>

                  <!-- Content -->
                  <p style="margin: 0 0 8px; font-size: 13px; line-height: 1.5;">{{ trait.content }}</p>

                  <!-- Confidence bar -->
                  <div style="margin-bottom: 6px;">
                    <div style="display: flex; justify-content: space-between; font-size: 11px; color: #999; margin-bottom: 2px;">
                      <span>置信度</span>
                      <span>{{ Math.round(trait.confidence * 100) }}%</span>
                    </div>
                    <div style="height: 4px; background: #f0f0f0; border-radius: 4px; overflow: hidden;">
                      <div style="height: 100%; border-radius: 4px; transition: width 0.3s;"
                           :style="`width: ${Math.round(trait.confidence * 100)}%; background: ${confidenceColor(trait.confidence)};`" />
                    </div>
                  </div>

                  <!-- Stats -->
                  <div style="display: flex; gap: 12px; font-size: 11px; color: #999;">
                    <span>+{{ trait.reinforcement_count }} / -{{ trait.contradiction_count }}</span>
                    <span>{{ new Date(trait.created_at).toLocaleDateString() }}</span>
                  </div>
                </div>

                <!-- Empty column placeholder -->
                <div v-if="groupByStage(stage).length === 0"
                     style="text-align: center; color: #d9d9d9; font-size: 12px; padding: 24px 0;">
                  —
                </div>
              </div>
            </div>
            <div v-if="i < allStages.length - 1"
                 style="width: 24px; flex-shrink: 0;" />
          </template>
        </div>
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import MemoryBaseSelector from '@/components/MemoryBaseSelector.vue'
import { listTraits, triggerDigest, type Trait } from '@/api/memory'
import { TRAIT_STAGE_COLORS, TRAIT_STAGE_LABELS } from '@/constants/memory'

// Left to right: candidate → trend → emerging → established → core
const allStages = ['candidate', 'trend', 'emerging', 'established', 'core']

const baseId = ref('')
const traits = ref<Trait[]>([])
const loading = ref(false)
const digesting = ref(false)

function onBaseChange(id: string) {
  baseId.value = id
  loadTraits()
}

async function runDigest() {
  if (!baseId.value || digesting.value) return
  digesting.value = true
  try {
    await triggerDigest(baseId.value)
    await loadTraits()
  } catch (e) {
    console.error('Digest failed', e)
  } finally {
    digesting.value = false
  }
}

async function loadTraits() {
  if (!baseId.value) return
  loading.value = true
  try {
    const { data } = await listTraits(baseId.value)
    traits.value = data
  } catch (e) {
    console.error('Failed to load traits', e)
    traits.value = []
  } finally {
    loading.value = false
  }
}

function groupByStage(stage: string) {
  return traits.value.filter(t => t.trait_stage === stage)
}

function confidenceColor(v: number): string {
  if (v >= 0.8) return '#386b47'
  if (v >= 0.5) return '#2a4d6a'
  if (v >= 0.3) return '#9a5b25'
  return '#c6333a'
}
</script>
