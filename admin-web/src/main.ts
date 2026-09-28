import { createApp } from 'vue'
import { createRouter, createWebHistory } from 'vue-router'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import './style.css'
import App from './App.vue'
import Login from './views/Login.vue'
import Communities from './views/Communities.vue'
import StaffLogin from './views/StaffLogin.vue'
import Space from './views/Space.vue'
import Billing from './views/Billing.vue'
import Occupants from './views/Occupants.vue'
import Notices from './views/Notices.vue'
import ServiceDesk from './views/ServiceDesk.vue'
import Votes from './views/Votes.vue'
import Inspection from './views/Inspection.vue'
import Finance from './views/Finance.vue'
import Team from './views/Team.vue'
import StaffChangePassword from './views/StaffChangePassword.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/login' },
    { path: '/login', component: Login },
    { path: '/communities', component: Communities },
    { path: '/staff-login', component: StaffLogin },
    { path: '/staff-change-password', component: StaffChangePassword },
    { path: '/space', component: Space },
    { path: '/billing', component: Billing },
    { path: '/occupants', component: Occupants },
    { path: '/team', component: Team },
    { path: '/import', redirect: { path: '/occupants', query: { tab: 'import' } } },
    { path: '/auth-review', redirect: { path: '/occupants', query: { tab: 'auth' } } },
    { path: '/room-changes', redirect: { path: '/occupants', query: { tab: 'changes' } } },
    { path: '/notices', component: Notices },
    { path: '/service-desk', component: ServiceDesk },
    { path: '/repairs', redirect: { path: '/service-desk', query: { kind: 'repair' } } },
    { path: '/complaints', redirect: { path: '/service-desk', query: { kind: 'complaint' } } },
    { path: '/votes', component: Votes },
    { path: '/inspection', component: Inspection },
    { path: '/finance', component: Finance },
  ],
})

createApp(App).use(router).use(ElementPlus).mount('#app')
