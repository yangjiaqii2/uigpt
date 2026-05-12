<script setup>
import { ref, onMounted, onUnmounted, computed } from 'vue'

const props = defineProps({
  size: { type: Number, default: 48 },
  pupilSize: { type: Number, default: 16 },
  maxDistance: { type: Number, default: 10 },
  eyeColor: { type: String, default: 'white' },
  pupilColor: { type: String, default: 'black' },
  isBlinking: { type: Boolean, default: false },
  forceLookX: { type: Number, default: undefined },
  forceLookY: { type: Number, default: undefined },
})

const eyeRef = ref(null)
const mouseX = ref(0)
const mouseY = ref(0)

function onMove(e) {
  mouseX.value = e.clientX
  mouseY.value = e.clientY
}

onMounted(() => window.addEventListener('mousemove', onMove))
onUnmounted(() => window.removeEventListener('mousemove', onMove))

const pupilPosition = computed(() => {
  const el = eyeRef.value
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
    ref="eyeRef"
    class="eye"
    :style="{
      width: `${size}px`,
      height: isBlinking ? '2px' : `${size}px`,
      backgroundColor: eyeColor,
    }"
  >
    <div
      v-if="!isBlinking"
      class="pupil-inner"
      :style="{
        width: `${pupilSize}px`,
        height: `${pupilSize}px`,
        backgroundColor: pupilColor,
        transform: `translate(${pupilPosition.x}px, ${pupilPosition.y}px)`,
      }"
    />
  </div>
</template>

<style scoped>
.eye {
  border-radius: 9999px;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.15s ease-out;
  overflow: hidden;
}

.pupil-inner {
  border-radius: 9999px;
  transition: transform 0.1s ease-out;
}
</style>
