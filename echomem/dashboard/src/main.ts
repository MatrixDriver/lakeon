import { createApp } from 'vue'
import { createPinia } from 'pinia'

import '@fontsource-variable/source-serif-4/index.css'
import '@fontsource-variable/geist/index.css'
import '@fontsource/jetbrains-mono/400.css'
import '@fontsource/jetbrains-mono/500.css'

import './styles/base.css'

import App from './App.vue'
import { router } from './router'

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.mount('#app')
