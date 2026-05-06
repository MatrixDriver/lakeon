import { defineStore } from 'pinia'
import { ref } from 'vue'
import { api } from '@/api/client'
import type { TimelineEvent, TimelineResponse, TreeNode, TreeResponse,
              GraphResponse, Skill, SkillsResponse } from '@/api/types'

export type CognitionSub = 'timeline' | 'summary' | 'graph' | 'skill'

export const useCognitionStore = defineStore('cognition', () => {
  const activeSub = ref<CognitionSub>('timeline')

  const timeline = ref<TimelineEvent[]>([])
  const summaryGroups = ref<Record<string, TreeNode[]>>({})
  const graphSeed = ref<string>('')
  const graphHops = ref<number>(2)
  const graph = ref<GraphResponse>({ nodes: [], edges: [] })
  const skills = ref<Skill[]>([])

  function setActiveSub(s: CognitionSub) { activeSub.value = s }

  async function loadTimeline(client = api) {
    const now = Date.now()
    const data = await client.get<TimelineResponse>('/derivatives/timeline', {
      start_ms: now - 7 * 86_400_000, end_ms: now,
    })
    timeline.value = data.events.sort((a, b) => b.window_end - a.window_end)
  }

  async function loadSummaryGroup(source_kind: string, source_ref: string, client = api) {
    const data = await client.get<TreeResponse>('/derivatives/tree', { source_kind, source_ref })
    summaryGroups.value[`${source_kind}:${source_ref}`] = data.levels
  }

  async function loadGraph(client = api) {
    if (!graphSeed.value) return
    const data = await client.get<GraphResponse>('/derivatives/graph', {
      seed: graphSeed.value, hops: graphHops.value,
    })
    graph.value = data
  }

  async function loadSkills(ctx: string, client = api) {
    const data = await client.get<SkillsResponse>('/derivatives/skills', { ctx, k: 20 })
    skills.value = data.skills
  }

  return {
    activeSub, timeline, summaryGroups,
    graphSeed, graphHops, graph, skills,
    setActiveSub, loadTimeline, loadSummaryGroup, loadGraph, loadSkills,
  }
})
