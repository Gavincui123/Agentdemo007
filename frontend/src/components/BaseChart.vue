<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, watch } from 'vue'
import * as echarts from 'echarts'
import type { EChartsOption, ECharts } from 'echarts'

/**
 * ECharts 薄包装（Phase 19）——init/dispose/resize 生命周期封装。
 *
 * <p>纯呈现组件：接受 {@link EChartsOption}，挂载即渲染、卸载即释放、尺寸变化即重排。
 * 不持业务状态——option 由可观测视图按 {@link ObservabilitySummary} 强类型计算后传入（④收口）。
 * 降级：option 为空 → 不渲染图表（视图层以空态兜底，②）。
 */
const props = defineProps<{ option: EChartsOption }>()
const el = ref<HTMLElement | null>(null)
let chart: ECharts | null = null
let ro: ResizeObserver | null = null

onMounted(() => {
  if (!el.value) return
  chart = echarts.init(el.value)
  chart.setOption(props.option)
  ro = new ResizeObserver(() => chart?.resize())
  ro.observe(el.value)
})

watch(
  () => props.option,
  (opt) => {
    chart?.setOption(opt, true)
  },
  { deep: true },
)

onBeforeUnmount(() => {
  ro?.disconnect()
  chart?.dispose()
  chart = null
})
</script>

<template>
  <div ref="el" class="base-chart" />
</template>

<style scoped>
.base-chart {
  width: 100%;
  height: 100%;
  min-height: 180px;
}
</style>
