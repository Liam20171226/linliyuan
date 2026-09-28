<script setup lang="ts">
import { onLaunch } from '@dcloudio/uni-app'
import { getToken, mustChangePassword } from './utils/auth'

onLaunch(() => {
  const token = getToken()
  if (!token) return
  if (mustChangePassword()) {
    uni.reLaunch({ url: '/pages/change-password/change-password' })
    return
  }
  uni.reLaunch({ url: '/pages/home/home' })
})
</script>
<style>
page {
  background-color: #f7faf8;
  color: #1f3d32;
  font-family: 'PingFang SC', 'HarmonyOS Sans', 'Noto Sans SC', sans-serif;
}
</style>
