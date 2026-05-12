<script setup>
import { ref, onMounted, onUnmounted, computed } from 'vue'

const props = defineProps({
  size: { type: Number, default: 12 },
  maxDistance: { type: Number, default: 5 },
  pupilColor: { type: String, default: 'black' },
  forceLookX: { type: Number, default: undefined },
  forceLookY: { type: Number, default: undefined },
})

const pupilRef = ref(null)
const mouseX = ref(0)
const mouseY = ref(0)

function onMove(e) {
  mouseX.value = e.clientX
  mouseY.value = e.clientY
}

onMounted(() => window.addEventListener('mousemove', onMove))
onUnmounted(() => window.removeEventListener('mousemove', onMove))

const pupilPosition = computed(() => {
  const el = pupilRef.value
  if (!el) return { x: 0, y: 0 }

  if (props.forceLookX !== undefined && props.forceLookY !== undefined) {
    return { x: props.forceLookX, y: props.forceLookY }
  }

  const rect = el.getBoundingClientRect()
  const cx = rect.left + rect.width / 2
  const cy = rect.top + rect.height / 2
  const deltaX = mouseX.value - cx
  const deltaY = mouseY.value - cy
  const distance = Math.min(Math.sqrt(deltaX ** 2 + deltaY ** 2), props.maxDistance)
  const angle = Math.atan2(deltaY, deltaX)
  return {
    x: Math.cos(angle) * distance,
    y: Math.sin(angle) * distance,
  }
})
</script>

<template>
  <div
    ref="pupilRef"
    class="pupil"
    :style="{
      width: `${size}px`,
      height: `${size}px`,
      backgroundColor: pupilColor,
      transform: `translate(${pupilPosition.x}px, ${pupilPosition.y}px)`,
    }"
  />
</template>

<style scoped>
.pupil {
  border-radius: 9999px;
  transition: transform 0.1s ease-out;
}
</style>
