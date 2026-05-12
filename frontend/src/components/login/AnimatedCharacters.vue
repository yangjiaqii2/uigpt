<!--
  角色动画源自 MIT 项目 CareerCompass：https://github.com/arsh342/careercompass
  对应源文件：src/components/ui/animated-characters.tsx
-->
<script setup>
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import EyeBall from './EyeBall.vue'
import Pupil from './Pupil.vue'

const props = defineProps({
  isTyping: { type: Boolean, default: false },
  showPassword: { type: Boolean, default: false },
  passwordLength: { type: Number, default: 0 },
})

const mouseX = ref(0)
const mouseY = ref(0)
const isPurpleBlinking = ref(false)
const isBlackBlinking = ref(false)
const isLookingAtEachOther = ref(false)
const isPurplePeeking = ref(false)

const purpleRef = ref(null)
const blackRef = ref(null)
const yellowRef = ref(null)
const orangeRef = ref(null)

let purpleBlinkTimer = null
let blackBlinkTimer = null
let lookTimer = null
let peekTimer = null
let blinkStopped = false

function onMove(e) {
  mouseX.value = e.clientX
  mouseY.value = e.clientY
}

function scheduleRandomBlink(setBlink) {
  const id = setTimeout(() => {
    if (blinkStopped) return
    setBlink(true)
    setTimeout(() => {
      if (blinkStopped) return
      setBlink(false)
      scheduleRandomBlink(setBlink)
    }, 150)
  }, Math.random() * 4000 + 3000)
  return id
}

onMounted(() => {
  window.addEventListener('mousemove', onMove)
  purpleBlinkTimer = scheduleRandomBlink((v) => {
    isPurpleBlinking.value = v
  })
  blackBlinkTimer = scheduleRandomBlink((v) => {
    isBlackBlinking.value = v
  })
})

onUnmounted(() => {
  blinkStopped = true
  window.removeEventListener('mousemove', onMove)
  clearTimeout(purpleBlinkTimer)
  clearTimeout(blackBlinkTimer)
  clearTimeout(lookTimer)
  clearTimeout(peekTimer)
})

watch(
  () => props.isTyping,
  (v) => {
    clearTimeout(lookTimer)
    if (v) {
      isLookingAtEachOther.value = true
      lookTimer = setTimeout(() => {
        isLookingAtEachOther.value = false
      }, 800)
    } else {
      isLookingAtEachOther.value = false
    }
  },
)

watch(
  () => [props.passwordLength, props.showPassword],
  () => {
    clearTimeout(peekTimer)
    isPurplePeeking.value = false
    if (props.passwordLength > 0 && props.showPassword) {
      const schedulePeek = () => {
        peekTimer = setTimeout(() => {
          isPurplePeeking.value = true
          setTimeout(() => {
            isPurplePeeking.value = false
            if (props.passwordLength > 0 && props.showPassword) schedulePeek()
          }, 800)
        }, Math.random() * 3000 + 2000)
      }
      schedulePeek()
    }
  },
)

function calcPos(el) {
  if (!el) return { faceX: 0, faceY: 0, bodySkew: 0 }
  const rect = el.getBoundingClientRect()
  const centerX = rect.left + rect.width / 2
  const centerY = rect.top + rect.height / 3
  const deltaX = mouseX.value - centerX
  const deltaY = mouseY.value - centerY
  const faceX = Math.max(-15, Math.min(15, deltaX / 20))
  const faceY = Math.max(-10, Math.min(10, deltaY / 30))
  const bodySkew = Math.max(-6, Math.min(6, -deltaX / 120))
  return { faceX, faceY, bodySkew }
}

const purplePos = computed(() => calcPos(purpleRef.value))
const blackPos = computed(() => calcPos(blackRef.value))
const yellowPos = computed(() => calcPos(yellowRef.value))
const orangePos = computed(() => calcPos(orangeRef.value))

const isHidingPassword = computed(() => props.passwordLength > 0 && !props.showPassword)
const pwdPeek = computed(() => props.passwordLength > 0 && props.showPassword)
</script>

<template>
  <div class="stage">
    <!-- Purple -->
    <div
      ref="purpleRef"
      class="char purple"
      :style="{
        transform: pwdPeek
          ? 'skewX(0deg)'
          : isLookingAtEachOther || isHidingPassword
            ? `skewX(${(purplePos.bodySkew || 0) - 12}deg) translateX(40px)`
            : `skewX(${purplePos.bodySkew || 0}deg)`,
        height: isTyping || isHidingPassword ? '440px' : '400px',
      }"
    >
      <div
        class="eyes-row"
        :style="{
          left: pwdPeek ? '20px' : isLookingAtEachOther ? '55px' : `${45 + purplePos.faceX}px`,
          top: pwdPeek ? '35px' : isLookingAtEachOther ? '65px' : `${40 + purplePos.faceY}px`,
        }"
      >
        <EyeBall
          :size="18"
          :pupil-size="7"
          :max-distance="5"
          eye-color="white"
          pupil-color="#2D2D2D"
          :is-blinking="isPurpleBlinking"
          :force-look-x="
            pwdPeek ? (isPurplePeeking ? 4 : -4) : isLookingAtEachOther ? 3 : undefined
          "
          :force-look-y="
            pwdPeek ? (isPurplePeeking ? 5 : -4) : isLookingAtEachOther ? 4 : undefined
          "
        />
        <EyeBall
          :size="18"
          :pupil-size="7"
          :max-distance="5"
          eye-color="white"
          pupil-color="#2D2D2D"
          :is-blinking="isPurpleBlinking"
          :force-look-x="
            pwdPeek ? (isPurplePeeking ? 4 : -4) : isLookingAtEachOther ? 3 : undefined
          "
          :force-look-y="
            pwdPeek ? (isPurplePeeking ? 5 : -4) : isLookingAtEachOther ? 4 : undefined
          "
        />
      </div>
    </div>

    <!-- Black -->
    <div
      ref="blackRef"
      class="char black"
      :style="{
        transform: pwdPeek
          ? 'skewX(0deg)'
          : isLookingAtEachOther
            ? `skewX(${(blackPos.bodySkew || 0) * 1.5 + 10}deg) translateX(20px)`
            : isTyping || isHidingPassword
              ? `skewX(${(blackPos.bodySkew || 0) * 1.5}deg)`
              : `skewX(${blackPos.bodySkew || 0}deg)`,
      }"
    >
      <div
        class="eyes-row black-eyes"
        :style="{
          left: pwdPeek ? '10px' : isLookingAtEachOther ? '32px' : `${26 + blackPos.faceX}px`,
          top: pwdPeek ? '28px' : isLookingAtEachOther ? '12px' : `${32 + blackPos.faceY}px`,
        }"
      >
        <EyeBall
          :size="16"
          :pupil-size="6"
          :max-distance="4"
          eye-color="white"
          pupil-color="#2D2D2D"
          :is-blinking="isBlackBlinking"
          :force-look-x="pwdPeek ? -4 : isLookingAtEachOther ? 0 : undefined"
          :force-look-y="pwdPeek ? -4 : isLookingAtEachOther ? -4 : undefined"
        />
        <EyeBall
          :size="16"
          :pupil-size="6"
          :max-distance="4"
          eye-color="white"
          pupil-color="#2D2D2D"
          :is-blinking="isBlackBlinking"
          :force-look-x="pwdPeek ? -4 : isLookingAtEachOther ? 0 : undefined"
          :force-look-y="pwdPeek ? -4 : isLookingAtEachOther ? -4 : undefined"
        />
      </div>
    </div>

    <!-- Orange -->
    <div
      ref="orangeRef"
      class="char orange"
      :style="{ transform: pwdPeek ? 'skewX(0deg)' : `skewX(${orangePos.bodySkew || 0}deg)` }"
    >
      <div
        class="pupils-row orange-pupils"
        :style="{
          left: pwdPeek ? '50px' : `${82 + (orangePos.faceX || 0)}px`,
          top: pwdPeek ? '85px' : `${90 + (orangePos.faceY || 0)}px`,
        }"
      >
        <Pupil :size="12" :max-distance="5" pupil-color="#2D2D2D" :force-look-x="pwdPeek ? -5 : undefined" :force-look-y="pwdPeek ? -4 : undefined" />
        <Pupil :size="12" :max-distance="5" pupil-color="#2D2D2D" :force-look-x="pwdPeek ? -5 : undefined" :force-look-y="pwdPeek ? -4 : undefined" />
      </div>
    </div>

    <!-- Yellow -->
    <div
      ref="yellowRef"
      class="char yellow"
      :style="{ transform: pwdPeek ? 'skewX(0deg)' : `skewX(${yellowPos.bodySkew || 0}deg)` }"
    >
      <div
        class="pupils-row yellow-pupils"
        :style="{
          left: pwdPeek ? '20px' : `${52 + (yellowPos.faceX || 0)}px`,
          top: pwdPeek ? '35px' : `${40 + (yellowPos.faceY || 0)}px`,
        }"
      >
        <Pupil :size="12" :max-distance="5" pupil-color="#2D2D2D" :force-look-x="pwdPeek ? -5 : undefined" :force-look-y="pwdPeek ? -4 : undefined" />
        <Pupil :size="12" :max-distance="5" pupil-color="#2D2D2D" :force-look-x="pwdPeek ? -5 : undefined" :force-look-y="pwdPeek ? -4 : undefined" />
      </div>
      <div
        class="mouth"
        :style="{
          left: pwdPeek ? '10px' : `${40 + (yellowPos.faceX || 0)}px`,
          top: pwdPeek ? '88px' : `${88 + (yellowPos.faceY || 0)}px`,
        }"
      />
    </div>
  </div>
</template>

<style scoped>
.stage {
  position: relative;
  width: min(100%, 520px);
  height: 400px;
  margin: 0 auto;
}

@media (min-width: 1024px) {
  .stage {
    transform: translateX(8px);
  }
}

.char {
  position: absolute;
  bottom: 0;
  transition: all 0.7s ease-in-out;
  transform-origin: bottom center;
}

.purple {
  left: 70px;
  width: 180px;
  background-color: #6c3ff5;
  border-radius: 10px 10px 0 0;
  z-index: 1;
}

.black {
  left: 240px;
  width: 120px;
  height: 310px;
  background-color: #2d2d2d;
  border-radius: 8px 8px 0 0;
  z-index: 2;
}

.orange {
  left: 0;
  width: 240px;
  height: 200px;
  z-index: 3;
  background-color: #ff9b6b;
  border-radius: 120px 120px 0 0;
}

.yellow {
  left: 310px;
  width: 140px;
  height: 230px;
  background-color: #e8d754;
  border-radius: 70px 70px 0 0;
  z-index: 4;
}

.eyes-row {
  position: absolute;
  display: flex;
  gap: 2rem;
  transition: all 0.7s ease-in-out;
}

.black-eyes {
  gap: 1.5rem;
}

.pupils-row {
  position: absolute;
  display: flex;
  gap: 2rem;
  transition: all 0.2s ease-out;
}

.orange-pupils {
  gap: 2rem;
}

.yellow-pupils {
  gap: 1.5rem;
}

.mouth {
  position: absolute;
  width: 5rem;
  height: 4px;
  background-color: #2d2d2d;
  border-radius: 9999px;
  transition: all 0.2s ease-out;
}
</style>
