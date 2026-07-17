# 麻将 Web 版

<p align="center">
  <img src="https://img.shields.io/badge/Java-17-ED8B00?logo=openjdk&logoColor=white" alt="Java 17">
  <img src="https://img.shields.io/badge/Spring_Boot-3.2.0-6DB33F?logo=springboot&logoColor=white" alt="Spring Boot 3.2">
  <img src="https://img.shields.io/badge/WebSocket-Real--time-010101?logo=socketdotio&logoColor=white" alt="WebSocket">
  <img src="https://img.shields.io/badge/Maven-3.9-C71A36?logo=apachemaven&logoColor=white" alt="Maven">
  <img src="https://img.shields.io/badge/HTML5-CSS3-JS-E34F26?logo=html5&logoColor=white" alt="HTML5 + CSS3 + JS">
  <img src="https://img.shields.io/badge/JDK-25_compatible-008CDD?logo=openjdk&logoColor=white" alt="JDK 25">
  <img src="https://img.shields.io/badge/platform-Web-4285F4?logo=googlechrome&logoColor=white" alt="Web">
</p>

麻将 Web 版是一款基于 **Spring Boot + WebSocket** 的实时多人麻将游戏，支持 **山东推倒胡**玩法。本项目为面向对象程序设计（Java）课程设计项目，采用原生 HTML/CSS/JS 前端，无需安装任何客户端，打开浏览器即可游玩。

游戏支持 2–4 人对战，实现了完整的摸牌、出牌、碰、杠、吃、胡牌流程，内置递归回溯胡牌检测算法和 AI 自动对战测试系统。房间状态通过 JSON 文件持久化，服务器重启后自动恢复未完成的对局。

## 在线体验

<p align="center">
  <a href="https://lomocat.xyz/games/mahjong/">
    <img src="https://img.shields.io/badge/🎮-立即游玩-lomocat.xyz-4285F4?style=for-the-badge" alt="在线体验">
  </a>
</p>

已部署于阿里云 ECS，支持 HTTPS/WSS 加密通信。

## 功能特性

### 房间系统
- **一键创建房间**：生成 6 位房间码（A–Z + 0–9），分享给好友即可加入
- **大厅浏览**：查看所有活跃房间，快速加入
- **房主权限**：仅房主可开始游戏、对局结束后重新开局

### 游戏玩法
- **完整山东推倒胡规则**：136 张牌（万/条/筒/风/箭），支持碰、明杠、暗杠、补杠、吃
- **操作优先级仲裁**：胡 > 杠 > 碰 > 吃，服务端自动判定
- **实时 WebSocket 通信**：出牌、操作、回合切换零延迟广播
- **听牌提示**：一键查看当前可听牌型
- **5 秒操作超时**：超时自动过牌，保证游戏流畅

### 智能与测试
- **AI 自动对战**：内置 4 机器人模拟完整牌局，可运行 N 局压力测试
- **自动测试接口**：`POST /api/test?games=100` 一键验证程序稳定性

### 数据持久化
- **自动存档**：每 5 秒自动保存房间状态
- **崩溃恢复**：服务器重启后扫描 `data/rooms/` 恢复所有未完成房间
- **关键操作即时存档**：出牌、碰杠吃胡后立即保存

## 快速开始

### 环境要求

- **JDK 17+**（兼容 JDK 25）
- **Maven 3.6+**（项目自带 Maven Wrapper，无需手动安装）

### 启动服务

```bash
# 克隆仓库
git clone https://github.com/LoMoCatAp/JavaMahjong_web.git
cd JavaMahjong_web

# 使用 Maven Wrapper 启动（推荐，无需安装 Maven）
./mvnw spring-boot:run

# 或者使用系统 Maven
mvn spring-boot:run

# 打包运行
mvn clean package -DskipTests
java -jar target/mahjong-1.0.0.jar
```

启动后访问 **http://localhost:8080** 即可进入游戏。

### 开始游戏

1. 打开浏览器访问 `http://localhost:8080`
2. 输入昵称，点击「创建房间」
3. 复制房间号发给朋友（或打开第二个浏览器窗口/隐身窗口）
4. 其他玩家输入房间号，点击「加入房间」
5. 房主点击「开始游戏」即可开局

>  使用两个浏览器窗口即可单人测试完整流程。

## 游戏规则

### 牌型组成（136 张）

| 花色 | 牌种 | 数量 |
|------|------|------|
| 万 | 1万 – 9万 | 各 4 张，共 36 张 |
| 条 | 1条 – 9条 | 各 4 张，共 36 张 |
| 筒 | 1筒 – 9筒 | 各 4 张，共 36 张 |
| 风牌 | 东、南、西、北 | 各 4 张，共 16 张 |
| 箭牌 | 中、发、白 | 各 4 张，共 12 张 |

### 基本规则

- 2–4 人对战，每人起始 13 张牌（庄家 14 张）
- 轮流摸牌出牌，最先组成 **4 面子 + 1 将牌** 者胡牌
- 面子可以是顺子（同花色连续三张）或刻子（三张相同）
- 操作优先级：**胡 > 杠 > 碰 > 吃**（吃仅限上家）

### 胡牌算法

采用**递归回溯拆解法**：

1. 将手牌转换为 `int[34]` 计数数组（34 种牌型各几张）
2. 枚举所有可能的将牌（数量 ≥ 2 的牌型）
3. 移除将牌后，递归检查剩余牌能否全部拆解为顺子或刻子
4. 时间复杂度 O(2⁵)，在 34 种牌型上为常数时间

## 项目结构

```
src/main/java/com/shandong/majong/
├── MajongApplication.java          # Spring Boot 启动类
├── model/
│   ├── Card.java                   # 牌实体（花色、数值、34种编码）
│   ├── Player.java                 # 玩家实体（手牌、副露、积分）
│   ├── MeldGroup.java              # 副牌组（碰/杠/吃的牌组）
│   └── GameRoom.java               # 游戏房间（状态机、牌墙、玩家管理）
├── service/
│   ├── GameService.java            # 核心游戏逻辑（胡牌/听牌算法、操作判定）
│   └── RoomManager.java            # 房间管理器（生命周期、定时存档、恢复）
├── controller/
│   ├── RoomController.java         # HTTP REST API（房间 CRUD）
│   └── GameWebSocketHandler.java   # WebSocket 处理器（实时游戏消息路由）
├── config/
│   └── WebSocketConfig.java        # WebSocket 端点注册
└── util/
    ├── CardUtil.java               # 牌工具（洗牌、计数数组、格式化）
    └── FileUtil.java               # JSON 文件读写（Jackson）

src/main/resources/
├── application.yml                 # 服务配置（端口、存档、超时）
└── static/
    ├── index.html                  # 登录 / 房间大厅页面
    └── game.html                   # 游戏主界面

data/rooms/                         # 房间 JSON 存档目录（自动生成）
md/                                 # 各模块详细文档（15 篇）
.md/                                # 项目总体文档（README / 技术文档 / 答辩问答）
```

## API 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/api/rooms` | 创建房间 |
| `POST` | `/api/rooms/{roomId}/join` | 加入房间 |
| `GET` | `/api/rooms/{roomId}` | 查询房间详情 |
| `GET` | `/api/rooms` | 列出所有活跃房间 |
| `GET` | `/api/health` | 健康检查 |
| `POST` | `/api/test?games=N` | 运行 N 局 AI 自动测试 |

## WebSocket 协议

**连接地址**：`ws://localhost:8080/ws/game`（生产环境 `wss://lomocat.xyz/ws/game`）

### 客户端 → 服务端

```json
{ "action": "CREATE_ROOM",  "playerName": "张三" }
{ "action": "JOIN_ROOM",    "roomId": "ABC123", "playerName": "李四" }
{ "action": "START_GAME" }
{ "action": "PLAY",         "card": "3W" }
{ "action": "PENG" }
{ "action": "GANG" }
{ "action": "CHI",          "card": "4W" }
{ "action": "HU" }
{ "action": "PASS" }
{ "action": "TING_HINT" }
{ "action": "LEAVE_ROOM" }
```

### 服务端 → 客户端

```json
{ "type": "ROOM_CREATED",      "roomId": "ABC123" }
{ "type": "PLAYER_JOINED",     "playerName": "李四", "players": [...] }
{ "type": "GAME_STARTED",      "hand": ["1W","2W",...], "currentPlayer": "张三" }
{ "type": "DRAW",              "player": "张三", "card": "3W" }
{ "type": "PLAY",              "player": "张三", "card": "5T" }
{ "type": "ACTION",            "player": "李四", "action": "PENG", "card": "5T" }
{ "type": "TURN",              "player": "李四" }
{ "type": "AVAILABLE_ACTIONS", "actions": ["HU","PENG","PASS"], "card": "5T" }
{ "type": "HU",                "winner": "张三" }
{ "type": "GAME_OVER",         "winner": "张三", "reason": "HU" }
{ "type": "TING_HINT",         "cards": ["1W","4W"], "display": ["1万","4万"] }
{ "type": "ERROR",             "message": "房间不存在" }
```

## 自动测试

```bash
# 运行 100 局 AI 自动对战
curl -X POST http://localhost:8080/api/test?games=100
```

返回统计结果：总局数、流局数、每位玩家胡牌次数、平均摸牌数、总耗时。AI 策略为优先打出孤张、有碰则碰、可胡则胡。

也可在代码中直接调用：

```java
GameService.testAutoPlay(100);  // 4 个 AI 机器人对战 100 局
```

## 浏览器快捷键

游戏界面支持键盘操作：

| 按键 | 操作 |
|------|------|
| `H` | 胡 |
| `G` | 杠 |
| `P` | 碰 |
| `C` | 吃 |
| `Space` | 过（PASS） |
| `T` | 听牌提示 |
| 点击手牌后点击打出 | 出牌 |

## 配置说明

编辑 `src/main/resources/application.yml`：

```yaml
mahjong:
  data-dir: ./data/rooms       # 房间存档目录
  auto-save-interval: 5        # 自动存档间隔（秒）
  turn-timeout: 30             # 出牌超时（秒）
  max-rooms: 50                # 最大房间数
```

## 数据位置

- 房间存档：`./data/rooms/room_XXXXXX.json`
- 每个 JSON 文件包含完整的房间状态（玩家手牌、牌墙、操作历史），服务器重启后自动恢复。

## 当前限制

- 仅支持山东推倒胡规则，不含花牌、百搭等变体
- 最大 50 个并发房间，每房间最多 4 人
- 房间数据仅保存于本地文件系统，未使用外部数据库
- 前端未适配移动端，建议在桌面浏览器中使用

## 作者

- 在线部署：[lomocat.xyz/games/mahjong](https://lomocat.xyz/games/mahjong/)
- Bug 与建议：[GitHub Issues](https://github.com/LoMoCatAp/JavaMahjong_web/issues)



## 许可证

本项目仅用于课程设计学习与交流目的，非商业用途。

---

<p align="center">
  <sub>🀇 🀈 🀉 🀊 🀋 🀌 🀍 🀎 🀏 · 麻将 · 🀇 🀈 🀉 🀊 🀋 🀌 🀍 🀎 🀏</sub>
</p>


---

> **文档版本**：v1.1
> **最后更新**：2026-07-16
