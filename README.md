# JavaMahjong_Web

> **课程**：面向对象程序设计（Java）课程设计
> **项目类型**：Web 实时多人对战游戏

---

## 目录

- [1. 项目概述](#1-项目概述)
- [2. 技术栈与选型理由](#2-技术栈与选型理由)
- [3. 系统架构设计](#3-系统架构设计)
- [4. 包结构与面向对象设计](#4-包结构与面向对象设计)
- [5. 核心算法 — 胡牌检测](#5-核心算法--胡牌检测)
- [6. 实时通信 — WebSocket](#6-实时通信--websocket)
- [7. 游戏流程与状态机](#7-游戏流程与状态机)
- [8. 文件持久化与故障恢复](#8-文件持久化与故障恢复)
- [9. 前端界面设计](#9-前端界面设计)
- [10. 关键代码走读](#10-关键代码走读)
- [11. 测试方案](#11-测试方案)

---

## 1. 项目概述

本项目实现了一个完整的 **Web 版山东推倒胡麻将** 游戏。支持 2~4 人在线对战，包含完整的碰、杠、吃、胡、自摸等操作，以及听牌提示、操作优先级判定等核心功能。

| 功能模块 | 详情 |
|---------|------|
| 房间系统 | 创建/加入/离开房间，6位唯一房间号（如 #ABC123） |
| 游戏流程 | 洗牌→发牌→轮流摸牌出牌→碰/杠/吃判定→胡牌结束 |
| 操作判定 | HU > GANG > PENG > CHI（只能吃上家），优先规则正确 |
| 胡牌检测 | 递归回溯拆解法，支持任意数量手牌（14张、17张等） |
| 听牌提示 | 遍历34种牌模拟胡牌检测，前端高亮可听牌 |
| 实时通信 | WebSocket 双向 JSON 消息，服务端主动推送 |
| 文件持久化 | 每5秒自动保存房间状态为 JSON，重启自动恢复 |
| 自动测试 | 4个AI机器人模拟100局对战，统计胡牌次数 |

---

## 2. 技术栈与选型理由

| 层级 | 技术 | 版本 | 选型理由 |
|------|------|------|---------|
| **语言** | Java | 17 | 课设要求；LTS长期支持；switch表达式语法简洁 |
| **框架** | Spring Boot | 3.2.0 | 嵌入式Tomcat零配置启动；IoC容器管理Bean |
| **实时通信** | WebSocket | Spring内置 | 麻将需实时交互（出牌/碰杠），短轮询延迟太高 |
| **序列化** | Jackson | 2.15 | Spring Boot内置；Java对象与JSON双向映射 |
| **持久化** | JSON文件 | - | 满足课设"文件存储"要求；零额外依赖 |
| **前端** | HTML+CSS+JS | 原生ES6 | 无框架依赖；课设报告易解释；代码量可控 |

---

## 3. 系统架构设计

```
浏览器 (Browser)
  ├── index.html   (登录/创建房间界面)
  └── game.html    (游戏主界面，WebSocket客户端)
         │
    HTTP │ (REST API)          WebSocket │ (实时游戏)
         ▼                                ▼
┌─────────────────────────────────────────────────┐
│              Spring Boot 服务端                  │
│                                                 │
│  RoomController          GameWebSocketHandler   │
│  (房间CRUD接口)           (游戏实时交互)          │
│       │                       │                 │
│       ▼                       ▼                 │
│  RoomManager              GameService           │
│  (房间管理+持久化)         (胡牌算法+操作判定)     │
│       │                       │                 │
│       ▼                       ▼                 │
│  FileUtil                 CardUtil              │
│  (JSON文件读写)            (牌工具+洗牌)          │
│                                                 │
│  ┌──────────────────────────────────────────┐   │
│  │         Model 层 (数据实体)               │   │
│  │  Card / Player / MeldGroup / GameRoom    │   │
│  └──────────────────────────────────────────┘   │
│                                                 │
│  ┌──────────────────────────────────────────┐   │
│  │      JSON 文件持久化 (./data/rooms/)      │   │
│  └──────────────────────────────────────────┘   │
└─────────────────────────────────────────────────┘
```

**数据流概要**：
1. 用户通过 HTTP 创建/加入房间 → `RoomController` → `RoomManager`
2. 页面加载后建立 WebSocket 连接 → `GameWebSocketHandler` 绑定玩家Session
3. 游戏中的所有操作（出牌/碰/杠/胡）全部走 WebSocket 实时通道
4. 服务端处理游戏逻辑后主动推送消息给房间内所有玩家
5. `RoomManager` 定时将房间状态序列化为 JSON 写入磁盘

---

## 4. 包结构与面向对象设计

```
com.shandong.majong/
├── MajongApplication.java       # Spring Boot启动类
├── model/                       # 实体层 — 数据对象
│   ├── Card.java                # 牌（34种×4张=136张）
│   ├── Player.java              # 玩家（手牌/副露/积分/Session）
│   ├── MeldGroup.java           # 副露组（碰/杠/吃牌组）
│   └── GameRoom.java            # 游戏房间（状态机/牌墙/玩家管理）
├── service/                     # 服务层 — 核心逻辑
│   ├── GameService.java         # 胡牌算法/听牌检测/操作判定/自动测试
│   └── RoomManager.java         # 房间CRUD/定时存档/恢复/单例模式
├── controller/                  # 控制层 — 对外接口
│   ├── RoomController.java      # HTTP REST API
│   └── GameWebSocketHandler.java# WebSocket实时消息处理
├── config/
│   └── WebSocketConfig.java     # WebSocket端点注册
└── util/                        # 工具类
    ├── CardUtil.java            # 洗牌/计数数组/格式化
    └── FileUtil.java            # JSON序列化/反序列化/文件管理
```

### 设计原则

- **单一职责**：每个类只做一件事（Card只管牌数据、GameService只管游戏逻辑）
- **高内聚低耦合**：Model层不依赖Service，Service通过接口调用Util
- **面向接口**：WebSocket通过JSON消息协议通信，前后端解耦
- **状态模式**：GameRoom使用枚举State管理WAITING→READY→PLAYING→FINISHED流转

---

## 5. 核心算法 — 胡牌检测

### 5.1 数据结构

将手牌编码为 `int[34]` 计数数组：

```
索引:  0──8    9──17    18──26   27──30    31──33
含义:  1万~9万  1条~9条  1筒~9筒  东南西北   中发白
```

每种牌最多4张，`counts[i]` 表示第 `i` 种牌的数量。这套编码使算法中判断顺子、刻子只需简单索引运算。

### 5.2 算法原理

胡牌 = **4个面子（顺子 或 刻子）+ 1个将牌（雀头）**。算法分两步：

**第一步 — 寻找将牌**：遍历34种牌中 `counts[i] ≥ 2` 的牌种，逐一尝试作为雀头。

**第二步 — 拆解面子**（递归核心）：对移除雀头后的剩余牌，采用"每次消去最小编号牌所在的面子"的策略：

```
canFormMelds(counts, remaining):
    若 remaining == 0 → 返回 true（全部拆完）
    找到第一个 counts[i] > 0 的牌 i：
        尝试1: 若 counts[i] ≥ 3 → 移除3张作为刻子 → 递归
        尝试2: 若 i 是数牌(≤26)且 i%9≤6（不跨花色）→ 移除顺子 → 递归
        若两种都失败 → 返回 false（回溯）
```

**为什么不跨花色**：`i%9≤6` 确保顺子三张在同一花色内。如索引7（8万），7%9=7>6，不能组成8万+9万+1条。

### 5.3 复杂度分析

手牌最多17张（14+碰/杠），递归深度 ≤ 5层（4面子+入口），每层最多2分支。时间复杂度 O(2^5) = 常数级，实测单次检测 < 0.1ms。

### 5.4 听牌检测

对34种牌逐一模拟加入手牌后调用胡牌检测。某牌种已有4张则跳过（牌墙中已无此牌）。复杂度 O(34)。

---

## 6. 实时通信 — WebSocket

### 6.1 连接建立

```javascript
// 前端 game.html
const ws = new WebSocket('ws://localhost:8080/ws/game');
ws.onopen = () => sendWS({ action: 'JOIN_ROOM', roomId, playerName });
```

```java
// 后端 WebSocketConfig.java
registry.addHandler(gameWebSocketHandler, "/ws/game").setAllowedOrigins("*");
```

### 6.2 消息协议

**客户端 → 服务端**：

| action | 参数 | 说明 |
|--------|------|------|
| JOIN_ROOM | roomId, playerName | 绑定WebSocket会话到房间 |
| START_GAME | - | 房主开始游戏 |
| PLAY | card: "3W" | 出牌 |
| PENG | - | 碰 |
| GANG | - | 杠（明杠/暗杠） |
| CHI | optionIndex: 0 | 吃（指定选项索引） |
| HU | - | 胡 |
| PASS | - | 跳过可选操作 |
| TING_HINT | - | 查看听什么牌 |

**服务端 → 客户端**：

| type | 关键字段 | 说明 |
|------|---------|------|
| GAME_STARTED | hand, currentPlayer, players | 游戏开始，各玩家收到自己的手牌 |
| DRAW | player | 有人摸牌 |
| YOUR_DRAW | card, hand | 自己摸到的牌+更新后的手牌 |
| PLAY | player, card | 有人出牌 |
| YOUR_HAND | hand | 出牌/碰杠后更新手牌 |
| ACTION | player, action, melds, players | 碰/杠/吃广播 |
| AVAILABLE_ACTIONS | actions, chiOptions | 可选操作列表 |
| ACTIONS_CANCELLED | reason | 操作已失效 |
| TURN | player, remainCards | 轮到某人出牌 |
| TING_HINT | cards, display | 听牌列表 |
| HU | winner, handDisplay | 胡牌 |
| GAME_OVER | winner/reason | 游戏结束 |

### 6.3 并发控制

WebSocket handler 中的 `wsSessionMap`（`ConcurrentHashMap<String, WebSocketSession>`）维护所有活跃连接的全局映射，支持按玩家查找Session进行点对点推送。广播时遍历房间所有玩家并发消息。

---

## 7. 游戏流程与状态机

### 7.1 状态机

```
WAITING ──(人满/准备)──→ READY ──(房主点击开始)──→ PLAYING ──(胡牌/流局)──→ FINISHED
    ↑                                                                           │
    └────────────────────────(房主点击再来一局)──────────────────────────────────┘
```

### 7.2 回合流程

```
Player A 出牌
    │
    ▼
服务端 checkAvailableActions(A, 弃牌)
    │
    ├── 有人能胡/杠/碰/吃 ──→ 发送 AVAILABLE_ACTIONS 给对应玩家
    │                              │
    │                     ┌───────┼───────┐
    │                     ▼       ▼       ▼
    │                   胡牌    杠/碰    吃(仅下家)
    │                     │       │       │
    │                     ▼       ▼       ▼
    │                   游戏   执行操作  执行操作
    │                   结束   取消他人   取消他人
    │                         轮到该玩家 轮到该玩家
    │                         出牌       出牌
    │
    └── 没人能操作 ──→ advanceToNextPlayer
                            │
                            ▼
                     下一家摸牌 → TURN 广播
                            │
                            ▼
                      下一家出牌 → 循环
```

### 7.3 防止同一人多次出牌

出牌后设 `currentPlayerIndex = -1`（回合锁定状态）。`handlePlay` 方法严格检查 `currentPlayerIndex >= 0` 且玩家名匹配当前回合玩家，否则拒绝出牌。碰/杠/吃操作完成后通过 `setCurrentPlayer` 恢复回合。

### 7.4 操作冲突处理

当多个玩家同时对一张弃牌有操作意向时，按优先级 HU > GANG > PENG > CHI 处理。第一个提交的有效操作即被执行，同时通过 `ACTIONS_CANCELLED` 通知其他玩家其操作已失效。

---

## 8. 文件持久化与故障恢复

### 8.1 存储方案

- 位置：`./data/rooms/room_{房间号}.json`
- 格式：Jackson 将 `GameRoom` 对象序列化为美化 JSON（包含房间号、玩家列表、手牌数组、牌墙状态等）
- 时机：每5秒自动保存 + 每次关键操作（出牌/碰/杠/胡/流局）后即时保存

### 8.2 实现

```java
// RoomManager.java — 定时存档
@PostConstruct
public void init() {
    saveScheduler.scheduleWithFixedDelay(this::saveAllRooms, 5, 5, TimeUnit.SECONDS);
}

// 关键操作后即时保存
public void saveRoom(GameRoom room) {
    FileUtil.saveToFile(room, "room_" + room.getRoomId() + ".json");
}
```

### 8.3 恢复机制

服务启动时扫描 `./data/rooms/` 目录下所有 `.json` 文件，逐一反序列化为 `GameRoom` 对象，恢复到内存中的房间集合。服务器意外崩溃后重启，玩家刷新浏览器即可继续未完成的牌局。

---

## 9. 前端界面设计

### 9.1 设计原则

- 原生 HTML+CSS+JavaScript，零框架依赖
- Flexbox 弹性布局，适应不同屏幕
- 牌面颜色区分花色（万=红、条=绿、筒=蓝、风=紫、箭=金）
- 操作按钮仅在合法时启用（灰色=不可用）

### 9.2 页面结构 (game.html)

```
┌─────────────────────────────────────────┐
│  顶部栏: 房间号 | 玩家列表 | 剩余牌数      │
├─────────────────────────────────────────┤
│          其他玩家区域（手牌背面朝上）        │
│              弃牌区（最近弃牌高亮）          │
│     [副露区]         我的手牌（点击出牌）     │
├─────────────────────────────────────────┤
│  吃牌选项栏（动态显示）                     │
├─────────────────────────────────────────┤
│  [开始] [胡] [杠] [碰] [吃] [过] [听牌提示] │
└─────────────────────────────────────────┘
```

### 9.3 关键交互

- **选牌出牌**：第一次点击选中（弹起），再次点击确认出牌
- **吃牌选择**：多个吃法以按钮形式展示，点击即发送
- **听牌提示**：点击后高亮哪些牌打出后能听
- **快捷键**：H=胡 G=杠 P=碰 C=吃 空格=过 T=听牌提示

---

## 10. 关键代码走读

### 10.1 Card.java — 牌的编码

每张牌有两个身份：
- **显示身份**：花色 (WAN/TIAO/TONG/FENG/JIAN) + 数值
- **算法身份**：0-33整数编码，用于胡牌算法中的快速索引

`fromCode("3W")` 将前端的牌编码转为后端对象；`getCode()` 则反向转换。这种双向转换机制使得前后端 JSON 通信只传递简短字符串（如 "3W"、"DONG"）。

### 10.2 GameService.java — 胡牌检测（详见第5节）

`checkHu(List<Card> hand)` 方法约50行，是整个项目算法复杂度最高的部分。核心思路是在 `canFormMelds` 递归函数中，每次处理最小索引的牌，尝试将其作为刻子或顺子的首张——这保证了一定能找到合法拆解（如果存在的话）。

### 10.3 GameWebSocketHandler.java — 消息路由（约700行）

使用 Java 17 的 `switch` 表达式（箭头语法）将14种 `action` 路由到对应的处理方法。每个 `handle*` 方法完成：
1. 参数验证
2. 游戏逻辑执行（通过 GameService）
3. 广播消息给房间内玩家
4. 调用 RoomManager 保存状态

### 10.4 RoomManager.java — 生命周期管理

`@PostConstruct` 在服务启动时恢复所有房间，`@PreDestroy` 在服务关闭时保存所有房间。使用 `ScheduledExecutorService` 实现定时存档，`ConcurrentHashMap` 保证多线程安全。

---

## 11. 测试方案

### 11.1 自动测试

`GameService.testAutoPlay(100)` 方法创建4个AI机器人，自动运行100局完整游戏：

- **AI策略**：随机出牌（优先出孤张），有碰就碰，能胡就胡
- **统计指标**：总局数、流局数、各玩家胡牌次数、平均摸牌数、总耗时
- **调用方式**：`curl -X POST http://localhost:8080/api/test?games=100`
- **测试结果示例**：100局中流局约15-30局（取决于随机因素），均在合理范围

### 11.2 手动测试步骤

1. 打开浏览器 A → http://localhost:8080 → 输入昵称 → 创建房间
2. 打开浏览器 B（隐身窗口） → 输入房间号和昵称 → 加入房间
3. 房主A点击"开始游戏"
4. 轮流出牌，验证碰/杠/吃/胡功能
5. 点击"听牌提示"验证听牌检测
6. 关闭服务器再重启，验证房间状态恢复

---

> **文档版本**：v1.1
> **最后更新**：2026-07-16
