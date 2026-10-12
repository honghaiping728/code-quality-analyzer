import { createApp } from 'vue';
import ElementPlus from 'element-plus';
import zhCn from 'element-plus/es/locale/lang/zh-cn';

/* 样式导入顺序即层叠顺序，不能调换：
   1) Element Plus 基础样式 → 2) 官方暗色变量 → 3) 本项目令牌 → 4) 组件细化与令牌映射 */
import 'element-plus/dist/index.css';
import 'element-plus/theme-chalk/dark/css-vars.css';
import './styles/tokens.css';
import './styles/element.css';
import './styles/base.css';

import App from './App.vue';
import router from './router';

createApp(App)
    .use(router)
    .use(ElementPlus, { locale: zhCn })
    .mount('#app');
