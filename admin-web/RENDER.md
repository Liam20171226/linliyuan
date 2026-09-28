# 管理端部署到 Render Static Site

API 已在：`https://linliyuan.onrender.com`  
本目录构建时读取 `.env.production` 中的 `VITE_API_BASE`。

## 你在 Render 上的操作（约 2 分钟）

1. 打开 https://dashboard.render.com  
2. **+ New** → **Static Site**  
3. 选仓库 **Liam20171226/linliyuan**  
4. 填写：

| 项 | 值 |
|---|---|
| Name | `linliyuan-admin`（任意） |
| Branch | `main` |
| **Root Directory** | `admin-web` |
| **Build Command** | `npm install && npm run build` |
| **Publish Directory** | `dist` |

5. Environment（可选，已有 `.env.production`；若要覆盖再加）：  
   `VITE_API_BASE` = `https://linliyuan.onrender.com/api/v1`  
6. 点 **Create Static Site** / Deploy  

完成后打开 Render 给的 `https://xxx.onrender.com`，用平台/物业账号登录即可。

免费静态站一般不休眠；API 免费机会休眠，首次打开管理端可能要等 API 醒几秒。
