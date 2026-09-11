import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import App from './App.vue'
import { router } from './router'
import './styles/tokens.css'

/**
 * 应用入口（Phase 16）。
 *
 * <p>装配 Pinia（状态）+ Router（hash 模式）+ Element Plus（组件库）。
 * 主题由 tokens.css 覆写 --el-* 令牌全局生效（靛蓝/青/琥珀遥测主题），
 * 无需 Element Plus dark 主题包。
 *
 * <p>注：当前为 Element Plus 全量引入（MVP）；生产单 jar 可改 unplugin-auto-import 按需引入以瘦身。
 */
const app = createApp(App)
app.use(createPinia())
app.use(router)
app.use(ElementPlus)
app.mount('#app')
