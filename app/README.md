# 邻里院 App（uni-app）

与微信小程序、管理端共用 `server/` 的 `/api/v1`。优先 **Android APK**；仓库内双端（小程序 + App）业务页已按产品决策对齐。

## 完成状态

- **双端 App 编码完成（决策范围）**：住户 / 业委 / 物业 / 游客入口已接入；Web 下发临时密码登录与改密；功能覆盖除支付外的主路径；Android-first；代码在本仓库 `app/`。
- **明确不在范围**：微信支付 / App 内支付（账单只读，不做拉起支付）。

## 登录

- `POST /auth/app/login`：手机号 + 密码（与 Web `password_hash` 共用）
- 物业在管理端「本房住户 → 设App密码」下发临时密码（默认 `linliyuan123`）
- 首次登录若 `mustChangePassword=true`，须先走改密页

## 业务页（已接入）

| 页面 | 路径 | 主要 API |
|------|------|----------|
| 首页 | `pages/home/home` | `GET /auth/identities`（按身份展示入口） |
| 公告 / 详情 | `pages/notices` · `notice-detail` | `GET /notices` · `GET /notices/{id}` |
| 账单 | `pages/bills/bills` | `GET /resident/bills`（支付暂未开放） |
| 服务台 / 提交 / 记录 / 详情 | `pages/service` · `service-submit` · `service-records` · `service-detail` | repairs / complaints + attachments + rate |
| 投票 / 明细 | `pages/votes` · `vote-detail` | `GET/POST /resident/votes…/ballots` |
| 发起投票 / 我的投票 / 统计 | `vote-create` · `vote-mine` · `vote-stats` | `POST /committee/votes` · `POST /staff/votes` · list · `GET /votes/{id}/stats` · `DELETE /votes/{id}` |
| 财务公开 / 公共收益 | `pages/finance` · `public-revenue` | finance/summary · public-revenue/items |
| 我的 | `pages/mine/mine` | identities · rooms · todos；含关于我们、建小区申请 |
| 建小区申请 | `pages/community-apply` | `GET/POST /community-applications` · `/mine` |
| 住户名册（业委） | `pages/occupants-roster` | `GET /committee/occupants`（物业身份可走 `/staff/occupants`） |
| 人员车辆（物业） | `pages/staff-people` | `GET /staff/occupants` · `/staff/vehicles`；变更跳转 `staff-changes` |
| 物业工单 / 详情 | `pages/staff-tickets` · `staff-ticket-detail` | `/staff/repairs/*` · `/staff/complaints/*`（含回复附图） |
| 巡检 / 详情 | `pages/staff-inspect` · `staff-inspect-detail` | `/staff/inspect/jobs` · `…/scan`（扫码+拍照） |
| 住户认证 / 申请记录 | `pages/auth-apply` · `approval-records` | 小区搜索 · space-tree · auth-applications |
| 房屋维护 / 变更申请 | `pages/room-maintain` · `room-change-apply` | rooms · 6 种 room-change-applications |
| 认证审核 / 变更审核 | `pages/staff-auth` · `staff-changes` | `/staff/auth-applications` · `/staff/room-change-applications` |
| 工单总览（业委） | `pages/complaint-overview` | `/committee/tickets`（fallback complaints/overview） |
| 预缴协议 | `pages/prepaid/prepaid` | `GET /resident/prepaid/plans`（只读） |
| 关于我们 | `pages/about/about` | `GET /about-us` |
| 通知管理 / 发布 | `pages/notice-manage` · `notice-create` | `/committee/notices` |

## 本地开发

```powershell
cd app
npm install
npm run dev:h5
# 或 Android 自定义基座 / 云打包前：
npm run dev:app-android
```

真机调试时，登录页可改 API 基址为电脑局域网 IP，例如 `http://192.168.137.1:8080/api/v1`。

## 打 APK（HBuilderX）

1. 本机已验证：`npm run build:app-android` → 产出 `dist/build/app/`（HBuilderX 提示：导入该目录运行 / 云打包）
2. 用 **HBuilderX** 打开本目录 `app/`（或导入上述 dist）
3. 菜单：**发行 → 原生 App-云打包**（或本地打包）→ 选 **Android**
4. 配置包名、证书（调试可用 DCloud 公共测试证书）
5. 安装 APK 后，登录页将 API 改为手机可达的后端地址

验收勾选见 [ACCEPTANCE.md](./ACCEPTANCE.md)。

## 范围外

- **微信支付 / 任何 App 内支付拉起**（不做）
