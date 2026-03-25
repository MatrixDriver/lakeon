<template>
  <div class="page-container">
    <div class="page-header">
      <h1 class="page-title">反思洞察</h1>
    </div>

    <MemoryBaseSelector @change="onBaseChange" />

    <div v-if="baseId" style="margin-top: 20px;">
      <p v-if="loading" style="text-align: center; color: #999; padding: 40px 0;">加载中...</p>
      <p v-else-if="traits.length === 0" style="text-align: center; color: #999; padding: 40px 0;">
        暂无洞察。请先执行记忆反思（digest）。
      </p>

      <template v-else>
        <!-- Main stages: core, established, emerging -->
        <div v-for="stage in TRAIT_STAGE_ORDER" :key="stage">
          <template v-if="groupByStage(stage).length > 0">
            <div style="display: flex; align-items: center; gap: 8px; margin: 24px 0 12px;">
              <h2 style="font-size: 16px; font-weight: 600; margin: 0;">{{ TRAIT_STAGE_LABELS[stage] }}</h2>
              <span style="font-size: 12px; color: #999; background: #f5f5f5; padding: 1px 8px; border-radius: 10px;">
                {{ groupByStage(stage).length }}
              </span>
            </div>
            <div style="display: flex; flex-direction: column; gap: 12px;">
              <div v-for="trait in groupByStage(stage)" :key="trait.id" class="card" style="padding: 16px;">
                <!-- Stage badge + subtype -->
                <div style="display: flex; align-items: center; gap: 8px; margin-bottom: 8px;">
                  <span style="display: inline-block; padding: 2px 8px; border-radius: 4px; font-size: 12px;"
                        :style="`background: ${TRAIT_STAGE_COLORS[trait.trait_stage]?.bg}; color: ${TRAIT_STAGE_COLORS[trait.trait_stage]?.text};`">
                    {{ TRAIT_STAGE_LABELS[trait.trait_stage] || trait.trait_stage }}
                  </span>
                  <span v-if="trait.trait_subtype" style="font-size: 12px; color: #999;">{{ trait.trait_subtype }}</span>
                </div>

                <!-- Content -->
                <p style="margin: 0 0 12px; font-size: 14px; line-height: 1.6;">{{ trait.content }}</p>

                <!-- Confidence bar -->
                <div style="margin-bottom: 12px;">
                  <div style="display: flex; justify-content: space-between; font-size: 12px; color: #999; margin-bottom: 4px;">
                    <span>置信度</span>
                    <span>{{ Math.round(trait.confidence * 100) }}%</span>
                  </div>
                  <div style="height: 6px; background: #f0f0f0; border-radius: 3px; overflow: hidden;">
                    <div style="height: 100%; border-radius: 3px; transition: width 0.3s;"
                         :style="`width: ${Math.round(trait.confidence * 100)}%; background: ${confidenceColor(trait.confidence)};`" />
                  </div>
                </div>

                <!-- Lifecycle progress -->
                <div style="display: flex; align-items: center; gap: 0; margin-bottom: 12px;">
                  <template v-for="(s, i) in lifecycleStages" :key="s">
                    <div style="display: flex; flex-direction: column; align-items: center; flex: 1;">
                      <div style="width: 12px; height: 12px; border-radius: 50%; border: 2px solid;"
                           :style="lifecycleStepStyle(trait.trait_stage, s)" />
                      <span style="font-size: 10px; color: #999; margin-top: 2px;">{{ TRAIT_STAGE_LABELS[s] }}</span>
                    </div>
                    <div v-if="i < lifecycleStages.length - 1"
                         style="flex: 1; height: 2px; margin-top: -14px;"
                         :style="`background: ${isStageReached(trait.trait_stage, lifecycleStages[i + 1] ?? '') ? '#1890ff' : '#e0e0e0'};`" />
                  </template>
                </div>

                <!-- Stats -->
                <div style="display: flex; gap: 16px; font-size: 12px; color: #999;">
                  <span>+{{ trait.reinforcement_count }} / -{{ trait.contradiction_count }}</span>
                  <span>{{ new Date(trait.created_at).toLocaleDateString() }}</span>
                </div>
              </div>
            </div>
          </template>
        </div>

        <!-- Earlier stages (collapsed) -->
        <div v-if="earlierTraits.length > 0" style="margin-top: 24px;">
          <button @click="showEarlier = !showEarlier"
                  style="display: flex; align-items: center; gap: 8px; background: none; border: none; cursor: pointer; padding: 0; font-size: 16px; font-weight: 600; color: #666;">
            <span style="transition: transform 0.2s;" :style="showEarlier ? 'transform: rotate(90deg);' : ''">▸</span>
            Earlier
            <span style="font-size: 12px; color: #999; background: #f5f5f5; padding: 1px 8px; border-radius: 10px;">
              {{ earlierTraits.length }}
            </span>
          </button>
          <div v-if="showEarlier" style="display: flex; flex-direction: column; gap: 12px; margin-top: 12px;">
            <!-- Same card template but simplified (no lifecycle bar) -->
            <div v-for="trait in earlierTraits" :key="trait.id" class="card" style="padding: 16px; opacity: 0.7;">
              <div style="display: flex; align-items: center; gap: 8px; margin-bottom: 8px;">
                <span style="display: inline-block; padding: 2px 8px; border-radius: 4px; font-size: 12px; background: #f5f5f5; color: #999;">
                  {{ TRAIT_STAGE_LABELS[trait.trait_stage] || trait.trait_stage }}
                </span>
              </div>
              <p style="margin: 0; font-size: 14px; color: #666;">{{ trait.content }}</p>
            </div>
          </div>
        </div>
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import MemoryBaseSelector from '@/components/MemoryBaseSelector.vue'
import { listTraits, type Trait } from '@/api/memory'
import { TRAIT_STAGE_ORDER, TRAIT_EARLIER_STAGES, TRAIT_STAGE_COLORS, TRAIT_STAGE_LABELS } from '@/constants/memory'

const lifecycleStages = ['trend', 'candidate', 'emerging', 'established', 'core']
const stageIndex = (s: string) => lifecycleStages.indexOf(s)

const baseId = ref('')
const traits = ref<Trait[]>([])
const loading = ref(false)
const showEarlier = ref(false)

function onBaseChange(id: string) {
  baseId.value = id
  loadTraits()
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

const earlierTraits = computed(() =>
  traits.value.filter(t => TRAIT_EARLIER_STAGES.includes(t.trait_stage as any))
)

function confidenceColor(v: number): string {
  if (v >= 0.8) return '#52c41a'
  if (v >= 0.5) return '#1890ff'
  if (v >= 0.3) return '#faad14'
  return '#f5222d'
}

function isStageReached(current: string, target: string): boolean {
  return stageIndex(current) >= stageIndex(target)
}

function lifecycleStepStyle(current: string, step: string): string {
  const reached = isStageReached(current, step)
  const isCurrent = current === step
  if (isCurrent) return 'background: #1890ff; border-color: #1890ff;'
  if (reached) return 'background: #1890ff; border-color: #1890ff; opacity: 0.5;'
  return 'background: #fff; border-color: #d9d9d9;'
}
</script>
