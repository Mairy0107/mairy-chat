import { createApp } from 'vue'
import App from './App.vue'
import './style.css'
import { router } from './router'

// 启动中文聊天室页面。
createApp(App).use(router).mount('#app')
