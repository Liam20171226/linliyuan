# 部署到 Railway（演示用）

本仓库 `server/` 已提供 `Dockerfile` + `railway.toml`。适合先把 API 挂到公网；额度用完会停，正式环境仍建议国内云主机。

## 0. 前置

1. 注册 [Railway](https://railway.app)（可用 GitHub 登录）  
2. 本机项目目前若还不是 Git 仓库，先推到 GitHub（Railway 从 GitHub 拉代码最省事）

```powershell
cd "c:\cursor project"
git init
git add .
git commit -m "chore: prepare Railway deploy"
# 在 GitHub 建空仓库后：
# git remote add origin https://github.com/你的账号/仓库.git
# git branch -M main
# git push -u origin main
```

## 1. 创建项目与 MySQL

1. Railway Dashboard → **New Project**  
2. **Add MySQL**（Database → MySQL）  
3. 打开 MySQL 服务 → **Variables**，记下（名称可能略有差异）：  
   - `MYSQLHOST` / `MYSQLPORT` / `MYSQLDATABASE` / `MYSQLUSER` / `MYSQLPASSWORD`  
   或一条 `MYSQL_URL`

## 2. 部署 API 服务

1. 同一项目 → **New Service** → **GitHub Repo**（选本仓库）  
2. 服务设置里 **Root Directory** 设为 `server`  
3. 构建方式会读 `server/Dockerfile`（已写好）  
4. 在该 Web 服务 **Variables** 里添加（把 MySQL 变量引用过来）：

| 变量 | 值 |
|---|---|
| `SPRING_PROFILES_ACTIVE` | `prod,mysql` |
| `JWT_SECRET` | 至少 32 位随机字符串 |
| `MYSQL_HOST` | `${{MySQL.MYSQLHOST}}`（在 Variables 里用 Railway 引用选 MySQL 的 HOST） |
| `MYSQL_PORT` | `${{MySQL.MYSQLPORT}}` |
| `MYSQL_DB` | `${{MySQL.MYSQLDATABASE}}` |
| `MYSQL_USER` | `${{MySQL.MYSQLUSER}}` |
| `MYSQL_PASSWORD` | `${{MySQL.MYSQLPASSWORD}}` |
| `MYSQL_USE_SSL` | `false` |
| `WX_MOCK` | `true` |
| `WX_PAY_MOCK` | `true` |
| `WX_APPID` | `wx-dev-appid`（演示可占位） |
| `WX_SECRET` | `wx-dev-secret` |
| `UPLOAD_DIR` | `/app/uploads` |

说明：Railway 界面里添加变量时，对 MySQL 项点 **Add Reference**，不要手抄内网地址。

5. **Deploy**。日志出现启动完成，且健康检查 `/api/v1/health` 通过。  
6. 服务 → **Settings → Networking → Generate Domain**，得到类似 `https://xxx.up.railway.app`。

API 基址：`https://你的域名/api/v1`  
探活：`https://你的域名/api/v1/health`

## 3. 初始化库表（必须）

空库不会自动跑 `sql/V1`～`V8`。任选其一：

- Railway MySQL → **Data / Query** 控制台，按顺序粘贴执行 `sql/V1` … `V8`  
- 或本机装好 `mysql` 客户端，用 Railway 提供的 **公网连接**（若有）导入  

顺序：`V1 → V2 → V2 drop → V3 → V4 → V5 → V6 → V7 → V8`（见 `doc/05`）。

## 4. 联调各端

- 管理端：`.env` / `VITE_API_BASE=https://你的域名/api/v1`  
- 小程序 / App：API 基址同上（小程序正式环境还需合法域名配置，演示可先开发者工具关校验）  
- 平台登录种子账号等仍以本地文档为准；**上线前务必改密**

## 5. 常见问题

| 现象 | 处理 |
|---|---|
| 构建超久 / OOM | Root Directory 必须是 `server`；或升级内存 |
| 连不上库 | 检查变量引用是否来自同一项目的 MySQL；`MYSQL_USE_SSL=false` |
| health 401/404 | 路径应为 `/api/v1/health` |
| 免费额度用尽 | 服务会停，需加信用卡或换国内机 |

本地验证镜像（可选）：

```powershell
cd "c:\cursor project\server"
docker build -t linliyuan-api .
docker run --rm -p 8080:8080 -e PORT=8080 -e JWT_SECRET=dev-secret-at-least-32-chars-long -e MYSQL_HOST=host.docker.internal -e MYSQL_USER=root -e MYSQL_PASSWORD=root -e MYSQL_DB=property_mgmt -e WX_MOCK=true -e WX_PAY_MOCK=true linliyuan-api
```
