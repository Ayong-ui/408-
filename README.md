# 修仙台

个人学习洞府：今日功课、入定计时、修为境界、灵力统计、修行日志、观星台，以及把 B 站网课字幕炼化成笔记的闻道阁。

需求见 [修仙台产品需求说明.md](修仙台产品需求说明.md)，接口见 [docs/API.md](docs/API.md)，闻道阁见 [docs/WENDAO.md](docs/WENDAO.md)。

## 启动

需要 JDK 17+ 和 Maven（本机都已装好）。双击 `start.bat`：

- 第一次会先构建，之后直接启动，约 8 秒后自动打开 http://127.0.0.1:7788/ 。
- 关掉那个黑窗口就停止。
- 改了代码要重新构建：`mvn -q -DskipTests package`，再双击 `start.bat`。

想开机自启：按 `Win + R`，输入 `shell:startup` 打开启动文件夹，放一个 `start.bat` 的快捷方式进去，在快捷方式"目标"末尾加上 ` quiet`（不自动弹浏览器）。

## 手机访问（Tailscale）

修仙台没有登录，所以**只监听本机 127.0.0.1**，局域网和公网都连不进来。手机通过 Tailscale 访问：它把电脑和手机连成一个只属于你的私有网络，只有登录了你账号的设备才进得来，流量全程加密。

1. 电脑装 Tailscale（https://tailscale.com/download/windows ），用 GitHub / Google / Microsoft 账号登录。
2. 手机装 Tailscale（iPhone 在 App Store；安卓在应用商店或 Tailscale 官网下载），**登录同一个账号**，打开连接。
3. 打开 Tailscale 管理后台 https://login.tailscale.com/admin/dns ，开启 **MagicDNS** 和 **HTTPS Certificates**。
4. 电脑上先启动修仙台，再在终端执行一次：
   ```text
   tailscale serve --bg 7788
   ```
   它会打印一个地址，形如 `https://你的电脑名.xxxx.ts.net`。`--bg` 表示常驻，重启电脑后依然有效。
5. 手机浏览器打开这个地址。再用浏览器菜单里的"添加到主屏幕"，修仙台就像一个独立应用。

注意：

- 电脑要开着、不能睡眠：设置 → 电源 → 屏幕和睡眠，按需要把"睡眠"改成"从不"；笔记本可以设成合盖不睡眠。
- **不要**用 ngrok、cloudflared 或路由器端口转发把 7788 暴露到公网。修仙台没有登录，暴露出去任何人都能进来。
- 取消手机访问：`tailscale serve reset`。
- 如果国内网络下 Tailscale 连接很慢，那是走了海外中继。只在宿舍、家里用的话，手机和电脑连同一个 Wi-Fi 时通常能直连。

手机和电脑看到的是同一份数据：在手机上入定，电脑上能看到香在燃；在电脑上出关，手机刷新后同步。

## 闻道阁（B 站字幕 → 学习笔记）

第一次用之前：

1. **B 站字幕必须登录才能拿到**（2026-10 实测，不登录时字幕列表全是空的）。所以先导出 cookie：
   1. 在电脑浏览器里登录 bilibili.com。
   2. 装一个能导出 cookies.txt 的扩展：Chrome / Edge 用 "Get cookies.txt LOCALLY"，Firefox 用 "cookies.txt"。
   3. 在 B 站页面上点扩展，导出 Netscape 格式的 cookies.txt，保存为项目里的 `config/bilibili-cookies.txt`。

   这个文件等同于你的 B 站登录态，不要发给别人。过期后闻道阁会提示，重新导出即可。
2. Claude Code 要已登录（命令行里能用 `claude`）。

然后在闻道阁贴视频链接，点"炼化"即可。笔记存在 `data/notes/`，可以用 Typora、Obsidian 打开（公式是 LaTeX）。

- 提示词在 `prompts/bilibili_note.md`，直接改，不用重新构建。样例效果见 [docs/sample-note.md](docs/sample-note.md)。
- 字幕会经 Claude 处理，也就是会发送到 Anthropic 的服务，不是纯本地处理。
- 经当前网关，一节 5 分钟的课大约要炼化 10 分钟。嫌慢可以在 `config/application.yml` 里换更快的模型（文件里有示例）。

## 数据

- 全部数据都在 `data/` 下：`xiuxian.db`（SQLite）、`notes/`（闻道阁笔记）、`subtitles/`（原始字幕）、`logs/`。
- 备份就是复制 `data` 文件夹。
- 本机配置写在 `config/application.yml`，比如端口、闻道阁模型和超时、笔记目录。

## 目录

```text
src/main/java/com/xiuxian   后端（Spring Boot + JdbcTemplate + SQLite）
src/main/resources/static   前端（Vue 3，无需构建；字体、Vue、marked 都已放在本地）
wendao/refine.py            闻道阁炼化脚本（.venv 里装了 yt-dlp）
prompts/bilibili_note.md    闻道阁提示词
docs/                       接口约定、闻道阁说明、样例笔记
```
