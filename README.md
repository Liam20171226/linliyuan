# 物业管理系统（邻里院）

文档：`doc/01` → `08`。一阶段按 **D.1→D.4** 实现（可编码范围已完成）。  
**正式上线：** 以 [doc/05-上线联调清单.md](./doc/05-上线联调清单.md) 为放行主清单；当前仍有 P0 未关闭（见该文档 §0）。

## 目录

```text
doc/          需求与设计、上线清单、测试
sql/          MySQL 增量 DDL（V1～V8；生产按 05 §3 顺序执行）
server/       Spring Boot 3 API（权威库表：src/main/resources/db/schema-h2.sql）
admin-web/    Vue3 管理端
miniapp/      微信原生小程序
app/          uni-app（Android APK 优先；与小程序共用 API；支付暂不做）
uploads/      本地附件
```

## 后端（本地 H2，无需先装 MySQL）

前置：JDK 17、Maven 3.9+

```powershell
$env:Path = "$env:USERPROFILE\tools\apache-maven-3.9.9\bin;" + $env:Path
cd server
mvn spring-boot:run
```

- API 基址：`http://localhost:8080/api/v1`
- Swagger：`http://localhost:8080/swagger-ui.html`
- 健康检查：`GET /api/v1/health`
- 平台登录：`POST /api/v1/auth/platform/login` body `{"username":"admin","password":"admin"}`
- 物业示例：`13800000001` / `staff123`（需先平台建账号并任职）

正式 MySQL：**不要**只执行 V1+V2。须按 [doc/05](./doc/05-上线联调清单.md) 补齐与 `schema-h2.sql` 对齐的基线后再 `--spring.profiles.active=prod,mysql`。

公网演示部署（Railway）：见 [`server/RAILWAY.md`](./server/RAILWAY.md)（`server/Dockerfile` 已就绪）。

## 前端

```powershell
cd admin-web
npm install
npm run dev
```

管理端（物业登录后）：空间 · 住户 · 导入 · 认证 · 变更 · 账单 · 财务 · 公告 · 服务台/工单 · 巡检 · 投票。

小程序：微信开发者工具打开 `miniapp/`（开发态 code/手机号 mock）。真机连电脑热点时 API 用 `http://192.168.137.1:8080/api/v1`；真机调试通道可用 `127.0.0.1`。上线改为 HTTPS 域名。

App（Android）：见 [`app/README.md`](./app/README.md) 与验收清单 [`app/ACCEPTANCE.md`](./app/ACCEPTANCE.md)。`npm run build:app-android` 已可产出 `app/dist/build/app`，再用 HBuilderX 云打包 APK。登录：手机号+密码；管理端「设App密码」。支付暂不做。

## 一阶段进度（完成）

### D.1 基础与住户
- [x] DDL / JWT / 平台·物业·小程序登录与身份切换
- [x] 开户、任职（含一线岗）、空间树、房屋类型、车位、业委会
- [x] 认证申请/审核、房屋变更、Excel 导入、直改+审计、附件、待办
- [x] 住户列表（脱敏）、我的房屋
- [x] admin-web / miniapp 业务页

### D.2 账单与缴费
- [x] 费项九大类/单价、表计、生成草稿、发放、单房重推
- [x] 豁免、冲红补收、合并支付；线下确认收款；微信商户支付 MOCK + notify
- [x] 预缴协议方案 A；缴费记录；admin / 小程序账单页

### D.3 / D.4
- [x] 经营收支与汇总；公共收益直接公示 API
- [x] 公告（含 Banner）、统一服务工单（报修/投诉）、巡检
- [x] 业主投票；admin / miniapp 对应页面

### 已知简化 / 上线前必读
- 本地默认 `app.wx.mock=true` / `app.wx.pay.mock=true`；去 MOCK 与完整勾选见 [doc/05](./doc/05-上线联调清单.md)
- 微信支付正式 JSAPI 下单待接官方 SDK（商户参数齐后）
- 管理端/小程序为可操作骨架，非完整视觉稿
- 测试结论见 [doc/08](./doc/08-测试报告.md)（有条件通过 · 非生产签字）

### 二阶段
- [ ] E 章文档完善后再开发
