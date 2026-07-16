package com.shandong.majong.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shandong.majong.model.Card;
import com.shandong.majong.model.GameRoom;
import com.shandong.majong.model.MeldGroup;
import com.shandong.majong.model.Player;
import com.shandong.majong.service.GameService;
import com.shandong.majong.service.RoomManager;
import com.shandong.majong.util.CardUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

/*
 WebSocket 消息处理器

 前端 → 后端 消息格式：{ "action": "CREATE_ROOM|JOIN_ROOM|START_GAME|PLAY|PENG|GANG|CHI|HU|PASS|TING_HINT", ... }
 后端 → 前端 消息格式：{ "type": "ROOM_CREATED|GAME_STARTED|DRAW|PLAY|HU|GAME_OVER|...", ... }
 */
@Component
public class GameWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(GameWebSocketHandler.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final RoomManager roomManager;
    private final GameService gameService;

    /*
      Session ID → (roomId, playerName) 映射
     */
    private final ConcurrentHashMap<String, SessionInfo> sessionMap = new ConcurrentHashMap<>();

    /*
      Session ID → WebSocketSession 全局映射（用于消息发送）
     */
    private final ConcurrentHashMap<String, WebSocketSession> wsSessionMap = new ConcurrentHashMap<>();

    /*
      操作超时定时器：玩家出牌后等5秒，无人响应自动跳过
     */
    private final java.util.concurrent.ScheduledExecutorService timeoutScheduler =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "action-timeout");
                t.setDaemon(true);
                return t;
            });

    /*
      房间号 → 超时任务
     */
    private final ConcurrentHashMap<String, java.util.concurrent.ScheduledFuture<?>> timeoutTasks = new ConcurrentHashMap<>();

    private static class SessionInfo {
        String roomId;
        String playerName;

        SessionInfo(String roomId, String playerName) {
            this.roomId = roomId;
            this.playerName = playerName;
        }
    }

    public GameWebSocketHandler(RoomManager roomManager, GameService gameService) {
        this.roomManager = roomManager;
        this.gameService = gameService;
    }

    //WebSocket 生命周期

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("WebSocket 连接建立: {}", session.getId());
        wsSessionMap.put(session.getId(), session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("WebSocket 连接关闭: {}, 状态: {}", session.getId(), status);
        wsSessionMap.remove(session.getId());
        SessionInfo info = sessionMap.remove(session.getId());//移除session并记录info玩家
        if (info != null) {
            handleLeaveRoom(session, info.roomId, info.playerName);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> msg = mapper.readValue(message.getPayload(), Map.class);
            String action = (String) msg.get("action");
            if (action == null) {
                sendError(session, "缺少 action 字段");
                return;
            }

            switch (action.toUpperCase()) {
                case "CREATE_ROOM" -> handleCreateRoom(session, msg);
                case "JOIN_ROOM" -> handleJoinRoom(session, msg);
                case "START_GAME" -> handleStartGame(session);
                case "PLAY" -> handlePlay(session, msg);
                case "PENG" -> handlePeng(session);
                case "GANG" -> handleGang(session);
                case "CHI" -> handleChi(session, msg);
                case "HU" -> handleHu(session);
                case "PASS" -> handlePass(session);
                case "TING_HINT" -> handleTingHint(session);
                case "LEAVE_ROOM" -> {
                    SessionInfo si = sessionMap.get(session.getId());
                    if (si != null) handleLeaveRoom(session, si.roomId, si.playerName);
                }
                default -> sendError(session, "未知操作: " + action);
            }
        } catch (Exception e) {
            log.error("处理消息失败: {}", message.getPayload(), e);
            sendError(session, "消息处理失败: " + e.getMessage());
        }
    }

    // 房间操作

    private void handleCreateRoom(WebSocketSession session, Map<String, Object> msg) {
        String playerName = (String) msg.getOrDefault("playerName", "");
        if (playerName.isEmpty()) {
            sendError(session, "昵称不能为空");
            return;
        }

        GameRoom room = roomManager.createRoom(playerName);
        if (room == null) {
            sendError(session, "创建房间失败");
            return;
        }

        sessionMap.put(session.getId(), new SessionInfo(room.getRoomId(), playerName));
        Player p = room.getPlayer(playerName);
        if (p != null) p.setSessionId(session.getId());

        sendToSession(session, "ROOM_CREATED", Map.of(//发送给浏览器
                "roomId", room.getRoomId(),
                "ownerName", playerName,
                "players", buildPlayerList(room)
        ));
        log.info("房间创建: {} by {}", room.getRoomId(), playerName);
    }

    private void handleJoinRoom(WebSocketSession session, Map<String, Object> msg) {
        String roomId = (String) msg.getOrDefault("roomId", "");
        String playerName = (String) msg.getOrDefault("playerName", "");
        if (roomId.isEmpty() || playerName.isEmpty()) {
            sendError(session, "房间号和昵称不能为空");
            return;
        }

        GameRoom room = roomManager.getRoom(roomId);
        if (room == null) {
            // 房间不存在，尝试通过 joinRoom 加入
            room = roomManager.joinRoom(roomId, playerName);
            if (room == null) {
                sendError(session, "房间不存在");
                return;
            }
        } else {
            // 房间存在，检查玩家是否已在房间中（HTTP 已加入的情况）
            Player existing = room.getPlayer(playerName);
            if (existing == null) {
                // 玩家不在房间中，尝试加入
                room = roomManager.joinRoom(roomId, playerName);
                if (room == null) {
                    sendError(session, "加入房间失败，可能已满");
                    return;
                }
            }
            // 玩家已在房间中，只需关联 WebSocket Session
        }

        // 关联 WebSocket Session
        sessionMap.put(session.getId(), new SessionInfo(roomId, playerName));
        Player p = room.getPlayer(playerName);
        if (p != null) p.setSessionId(session.getId());

        log.info("玩家 {} WebSocket 绑定成功，房间 {}", playerName, roomId);

        // 通知加入者
        sendToSession(session, "JOINED", Map.of(
                "roomId", roomId,
                "playerName", playerName,
                "ownerName", room.getOwnerName(),
                "players", buildPlayerList(room)
        ));
        // 广播给房间内其他人
        broadcastToRoom(room, "PLAYER_JOINED", Map.of(
                "playerName", playerName,
                "playerCount", room.getPlayerCount(),
                "players", buildPlayerList(room)
        ), playerName);
    }

    // 游戏操作

    private void handleStartGame(WebSocketSession session) {
        SessionInfo info = sessionMap.get(session.getId());
        if (info == null) {
            sendError(session, "你不在任何房间中");
            return;
        }

        GameRoom room = roomManager.getRoom(info.roomId);
        if (room == null) {
            sendError(session, "房间不存在");
            return;
        }
        if (!room.getOwnerName().equals(info.playerName)) {
            sendError(session, "只有房主可以开始游戏");
            return;
        }
        if (!room.canStart()) {
            sendError(session, "人数不足或玩家未准备");
            return;
        }

        gameService.initGame(room);

        // 给每个玩家发送各自的初始手牌
        for (Player p : room.getPlayers()) {
            if (p.getSessionId() != null) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("hand", p.getHand().stream().map(Card::getCode).toList());
                data.put("currentPlayer", room.getCurrentPlayer().getName());
                data.put("dealer", room.getDealer() != null ? room.getDealer().getName() : "");
                data.put("remainCards", room.getRemainingCards());
                data.put("players", buildPlayerList(room));
                sendToSessionId(p.getSessionId(), "GAME_STARTED", data);
            }
        }

        // 广播游戏开始
        broadcastToRoom(room, "GAME_BEGIN", Map.of(
                "dealer", room.getDealer() != null ? room.getDealer().getName() : "",
                "currentPlayer", room.getCurrentPlayer().getName(),
                "remainCards", room.getRemainingCards()
        ), null);

        log.info("游戏开始: {}", room.getRoomId());
    }

    private void handlePlay(WebSocketSession session, Map<String, Object> msg) {
        SessionInfo info = sessionMap.get(session.getId());
        if (info == null) return;
        GameRoom room = roomManager.getRoom(info.roomId);
        if (room == null || room.getState() != GameRoom.State.PLAYING) return;

        Player player = room.getPlayer(info.playerName);
        if (player == null) return;

        // 只有当前回合的玩家才能出牌
        if (room.getCurrentPlayerIndex() < 0
                || !player.getName().equals(room.getCurrentPlayer().getName())) {
            sendError(session, "还没轮到你出牌");
            return;
        }

        String cardCode = (String) msg.get("card");
        if (cardCode == null) {
            sendError(session, "请指定要出的牌");
            return;
        }

        Card card;
        try {
            card = Card.fromCode(cardCode);
        } catch (Exception e) {
            sendError(session, "无效的牌: " + cardCode);
            return;
        }

        if (!player.getHand().contains(card)) {
            sendError(session, "你手里没有这张牌");
            return;
        }

        // 执行出牌
        gameService.executePlay(player, card);
        room.setLastDiscard(card);
        room.setLastDiscardPlayer(player.getName());
        room.clearPendingActions();
        cancelActionTimeout(room);

        // 出牌后立即清除当前玩家，防止同一个人二次出牌
        room.setCurrentPlayerIndex(-1);

        // 广播出牌（含出牌者更新后的手牌）
        Map<String, Object> playData = new LinkedHashMap<>();
        playData.put("player", player.getName());
        playData.put("card", card.getCode());
        playData.put("remainCards", room.getRemainingCards());
        playData.put("players", buildPlayerList(room));
        broadcastToRoom(room, "PLAY", playData, null);

        // 给出牌者单独发送更新后的手牌
        if (player.getSessionId() != null) {
            sendToSessionId(player.getSessionId(), "YOUR_HAND", Map.of(
                    "hand", player.getHand().stream().map(Card::getCode).toList()
            ));
        }

        // 检查其他玩家可以做什么（内部会决定是否 advanceToNextPlayer）
        checkAvailableActions(room, player, card);
    }

    private void handlePeng(WebSocketSession session) {
        SessionInfo info = sessionMap.get(session.getId());
        if (info == null) return;
        GameRoom room = roomManager.getRoom(info.roomId);
        if (room == null || room.getLastDiscard() == null) return;

        Player player = room.getPlayer(info.playerName);
        if (player == null) return;

        // 验证该玩家当前确实可以碰
        boolean hasPengAction = room.getPendingActions().stream()
                .anyMatch(a -> a.getPlayerName().equals(player.getName())
                        && a.getActionType() == GameRoom.ActionType.PENG);
        if (!hasPengAction && !gameService.canPeng(player, room.getLastDiscard())) {
            sendError(session, "无法碰这张牌");
            return;
        }

        Card target = room.getLastDiscard();
        gameService.executePeng(player, target);
        room.setLastDiscard(null);
        room.clearPendingActions();
        cancelActionTimeout(room);

        Map<String, Object> actionData = new LinkedHashMap<>();
        actionData.put("player", player.getName());
        actionData.put("action", "PENG");
        actionData.put("card", target.getCode());
        actionData.put("melds", buildMeldList(player));
        actionData.put("players", buildPlayerList(room));
        broadcastToRoom(room, "ACTION", actionData, null);

        // 通知所有等待者操作已取消
        cancelOtherActions(room, player.getName());

        // 碰完后轮到该玩家出牌（更新手牌给碰牌者）
        if (player.getSessionId() != null) {
            sendToSessionId(player.getSessionId(), "YOUR_HAND", Map.of(
                    "hand", player.getHand().stream().map(Card::getCode).toList()
            ));
        }
        setCurrentPlayer(room, player);
    }

    private void handleGang(WebSocketSession session) {
        SessionInfo info = sessionMap.get(session.getId());
        if (info == null) return;
        GameRoom room = roomManager.getRoom(info.roomId);
        if (room == null) return;

        Player player = room.getPlayer(info.playerName);
        if (player == null) return;

        // 验证该玩家当前确实可以杠
        boolean hasGangAction = room.getPendingActions().stream()
                .anyMatch(a -> a.getPlayerName().equals(player.getName())
                        && a.getActionType() == GameRoom.ActionType.GANG);

        // 判断是明杠还是暗杠
        if (room.getLastDiscard() != null
                && (hasGangAction || gameService.canMingGang(player, room.getLastDiscard()))) {
            // 明杠
            Card target = room.getLastDiscard();
            gameService.executeMingGang(player, target);
            room.setLastDiscard(null);
            room.clearPendingActions();
            cancelActionTimeout(room);

            Map<String, Object> actionData = new LinkedHashMap<>();
            actionData.put("player", player.getName());
            actionData.put("action", "GANG");
            actionData.put("card", target.getCode());
            actionData.put("type", "MING");
            actionData.put("melds", buildMeldList(player));
            actionData.put("players", buildPlayerList(room));
            broadcastToRoom(room, "ACTION", actionData, null);

            cancelOtherActions(room, player.getName());

            // 杠后从牌墙补一张
            Card drawn = room.drawCard();
            if (drawn != null) player.addCard(drawn);

            if (player.getSessionId() != null) {
                sendToSessionId(player.getSessionId(), "YOUR_HAND", Map.of(
                        "hand", player.getHand().stream().map(Card::getCode).toList()
                ));
            }
            setCurrentPlayer(room, player);
        } else {
            // 尝试暗杠（只能在自己回合进行）
            if (room.getCurrentPlayerIndex() < 0
                    || !player.getName().equals(room.getCurrentPlayer().getName())) {
                sendError(session, "现在不能暗杠");
                return;
            }
            List<Card> options = gameService.getAnGangOptions(player);
            if (options.isEmpty()) {
                sendError(session, "无法杠");
                return;
            }
            Card target = options.get(0);
            gameService.executeAnGang(player, target);
            room.clearPendingActions();
            cancelActionTimeout(room);

            Map<String, Object> actionData = new LinkedHashMap<>();
            actionData.put("player", player.getName());
            actionData.put("action", "GANG");
            actionData.put("card", target.getCode());
            actionData.put("type", "AN");
            actionData.put("melds", buildMeldList(player));
            actionData.put("players", buildPlayerList(room));
            broadcastToRoom(room, "ACTION", actionData, null);

            Card drawn = room.drawCard();
            if (drawn != null) player.addCard(drawn);

            if (player.getSessionId() != null) {
                sendToSessionId(player.getSessionId(), "YOUR_HAND", Map.of(
                        "hand", player.getHand().stream().map(Card::getCode).toList()
                ));
            }
            setCurrentPlayer(room, player);
        }
    }

    private void handleChi(WebSocketSession session, Map<String, Object> msg) {
        SessionInfo info = sessionMap.get(session.getId());
        if (info == null) return;
        GameRoom room = roomManager.getRoom(info.roomId);
        if (room == null || room.getLastDiscard() == null) return;

        Player player = room.getPlayer(info.playerName);
        if (player == null) return;

        // 验证该玩家当前确实可以吃
        boolean hasChiAction = room.getPendingActions().stream()
                .anyMatch(a -> a.getPlayerName().equals(player.getName())
                        && a.getActionType() == GameRoom.ActionType.CHI);
        if (!hasChiAction) {
            sendError(session, "现在不能吃");
            return;
        }

        // 只有下家能吃
        Player discardPlayer = room.getPlayer(room.getLastDiscardPlayer());
        if (discardPlayer == null) return;
        int nextPos = room.getNextPosition(discardPlayer.getPosition());
        if (player.getPosition() != nextPos) {
            sendError(session, "只能吃上家的牌");
            return;
        }

        Card discardCard = room.getLastDiscard();
        List<List<Card>> chiOptions = gameService.canChi(player, discardCard);

        if (chiOptions.isEmpty()) {
            sendError(session, "无法吃这张牌");
            return;
        }

        // 使用 optionIndex 精确指定吃法（前端展示选项供用户选择）
        int optionIndex = 0;
        Object idxObj = msg.get("optionIndex");
        if (idxObj instanceof Number) {
            optionIndex = ((Number) idxObj).intValue();
        }
        if (optionIndex < 0 || optionIndex >= chiOptions.size()) {
            optionIndex = 0; // 默认选第一个
        }

        List<Card> chosenChi = chiOptions.get(optionIndex);
        gameService.executeChi(player, discardCard, chosenChi);
        room.setLastDiscard(null);
        room.clearPendingActions();
        cancelActionTimeout(room);

        Map<String, Object> actionData = new LinkedHashMap<>();
        actionData.put("player", player.getName());
        actionData.put("action", "CHI");
        actionData.put("cards", chosenChi.stream().map(Card::getCode).toList());
        actionData.put("melds", buildMeldList(player));
        actionData.put("players", buildPlayerList(room));
        broadcastToRoom(room, "ACTION", actionData, null);

        cancelOtherActions(room, player.getName());

        // 吃后轮到该玩家出牌
        if (player.getSessionId() != null) {
            sendToSessionId(player.getSessionId(), "YOUR_HAND", Map.of(
                    "hand", player.getHand().stream().map(Card::getCode).toList()
            ));
        }
        setCurrentPlayer(room, player);
    }

    private void handleHu(WebSocketSession session) {
        SessionInfo info = sessionMap.get(session.getId());
        if (info == null) return;
        GameRoom room = roomManager.getRoom(info.roomId);
        if (room == null) return;

        Player player = room.getPlayer(info.playerName);
        if (player == null) return;

        boolean canHu = false;
        boolean isDiscardHu = false;

        // 情况1：胡别人打出的牌（必须有待处理的 HU 操作）
        if (room.getLastDiscard() != null) {
            boolean hasHuAction = room.getPendingActions().stream()
                    .anyMatch(a -> a.getPlayerName().equals(player.getName())
                            && a.getActionType() == GameRoom.ActionType.HU);
            if (hasHuAction) {
                canHu = gameService.canHu(player, room.getLastDiscard());
                if (canHu) {
                    player.addCard(room.getLastDiscard());
                    isDiscardHu = true;
                }
            }
        }

        // 情况2：自摸胡（必须是自己回合且摸牌后）
        if (!canHu && room.getCurrentPlayerIndex() >= 0
                && player.getName().equals(room.getCurrentPlayer().getName())) {
            canHu = gameService.checkHu(player.getHand());
        }

        if (!canHu) {
            sendError(session, "当前不能胡牌");
            return;
        }

        // 游戏结束
        room.setState(GameRoom.State.FINISHED);
        room.clearPendingActions();
        cancelActionTimeout(room);

        Map<String, Object> huData = new LinkedHashMap<>();
        huData.put("winner", player.getName());
        huData.put("hand", player.getHand().stream().map(Card::getCode).toList());
        huData.put("handDisplay", CardUtil.formatHand(player.getHand()));
        huData.put("melds", buildMeldList(player));

        broadcastToRoom(room, "HU", huData, null);
        broadcastToRoom(room, "GAME_OVER", Map.of(
                "winner", player.getName(),
                "reason", "胡牌"
        ), null);

        // 保存房间状态
        roomManager.saveRoom(room);
        log.info("{} 胡牌了！房间 {}", player.getName(), room.getRoomId());
    }

    private void handlePass(WebSocketSession session) {
        SessionInfo info = sessionMap.get(session.getId());
        if (info == null) return;
        GameRoom room = roomManager.getRoom(info.roomId);
        if (room == null) return;

        // 移除该玩家的所有待处理操作
        room.getPendingActions().removeIf(a -> a.getPlayerName().equals(info.playerName));
        room.removeWaitingPlayer(info.playerName);

        // 所有等待的玩家都已响应
        if (room.isAllResponded()) {
            GameRoom.PendingAction best = room.getBestPendingAction();
            if (best == null || best.getActionType() == GameRoom.ActionType.PASS) {
                advanceToNextPlayer(room);
            }
            // 如果有有效操作，等待该玩家点击按钮（已在 checkAvailableActions 中通知）
        }
    }

    private void handleTingHint(WebSocketSession session) {
        SessionInfo info = sessionMap.get(session.getId());
        if (info == null) return;
        GameRoom room = roomManager.getRoom(info.roomId);
        if (room == null) return;

        Player player = room.getPlayer(info.playerName);
        if (player == null) return;

        // 只有自己的回合才能查看听牌提示
        if (room.getCurrentPlayerIndex() < 0
                || !player.getName().equals(room.getCurrentPlayer().getName())) {
            sendError(session, "还没轮到你");
            return;
        }

        List<Card> tingCards = gameService.checkTing(player.getHand());
        sendToSession(session, "TING_HINT", Map.of(
                "cards", tingCards.stream().map(Card::getCode).toList(),
                "display", tingCards.stream().map(Card::getDisplayName).toList()
        ));
    }

    private void handleLeaveRoom(WebSocketSession session, String roomId, String playerName) {
        GameRoom room = roomManager.leaveRoom(roomId, playerName);
        if (room != null) {
            broadcastToRoom(room, "PLAYER_LEFT", Map.of(
                    "playerName", playerName,
                    "players", buildPlayerList(room)
            ), null);
        }
    }

    // 游戏逻辑辅助

    private void checkAvailableActions(GameRoom room, Player discardPlayer, Card discardedCard) {
        room.clearPendingActions();
        cancelActionTimeout(room);

        // 对每个其他玩家检查可执行的操作
        for (Player p : room.getPlayers()) {
            if (p == discardPlayer) continue;

            boolean canHu = gameService.canHu(p, discardedCard);
            boolean canGang = gameService.canMingGang(p, discardedCard);
            boolean canPeng = gameService.canPeng(p, discardedCard);
            boolean canChi = p.getPosition() == room.getNextPosition(discardPlayer.getPosition())
                    && !gameService.canChi(p, discardedCard).isEmpty();

            Map<String, Object> actions = new LinkedHashMap<>();
            actions.put("player", p.getName());
            List<String> available = new ArrayList<>();
            if (canHu) available.add("HU");
            if (canGang) available.add("GANG");
            if (canPeng) available.add("PENG");
            if (canChi) available.add("CHI");
            available.add("PASS");
            actions.put("actions", available);
            actions.put("card", discardedCard.getCode());

            // 包含吃牌选项（前端直接渲染为按钮，无需 prompt 弹窗）
            if (canChi) {
                List<List<Card>> chiOpts = gameService.canChi(p, discardedCard);
                List<Map<String, Object>> chiOptionsJson = new ArrayList<>();
                for (List<Card> opt : chiOpts) {
                    chiOptionsJson.add(Map.of(
                            "cards", opt.stream().map(Card::getCode).toList(),
                            "display", opt.stream().map(Card::getDisplayName).reduce((a, b) -> a + " " + b).orElse("")
                    ));
                }
                actions.put("chiOptions", chiOptionsJson);
            }

            // 通知该玩家可选的操作
            if (p.getSessionId() != null && (canHu || canGang || canPeng || canChi)) {
                sendToSessionId(p.getSessionId(), "AVAILABLE_ACTIONS", actions);
                room.addWaitingPlayer(p.getName());
            }

            if (canHu) room.addPendingAction(
                    new GameRoom.PendingAction(p.getName(), GameRoom.ActionType.HU, discardedCard));
            if (canGang) room.addPendingAction(
                    new GameRoom.PendingAction(p.getName(), GameRoom.ActionType.GANG, discardedCard));
            if (canPeng) room.addPendingAction(
                    new GameRoom.PendingAction(p.getName(), GameRoom.ActionType.PENG, discardedCard));
            if (canChi) room.addPendingAction(
                    new GameRoom.PendingAction(p.getName(), GameRoom.ActionType.CHI, discardedCard));
        }

        // 如果没有任何可用操作（没人能碰杠胡吃），直接进入下家
        if (room.getWaitingForPlayers().isEmpty()) {
            advanceToNextPlayer(room);
        } else {
            // 有人可以操作 → 启动5秒超时，超时后自动帮所有人PASS
            scheduleActionTimeout(room);
        }
    }

    /**
     * 启动操作超时：5秒后自动帮所有等待中的玩家PASS
     */
    private void scheduleActionTimeout(GameRoom room) {
        cancelActionTimeout(room);  // 先取消旧的
        var task = timeoutScheduler.schedule(() -> {
            log.info("房间 {} 操作超时，自动跳过等待玩家", room.getRoomId());
            timeoutTasks.remove(room.getRoomId());
            // 帮所有还在等待的玩家自动PASS
            for (String playerName : new ArrayList<>(room.getWaitingForPlayers())) {
                room.getPendingActions().removeIf(a -> a.getPlayerName().equals(playerName));
                room.removeWaitingPlayer(playerName);
            }
            advanceToNextPlayer(room);
        }, 5, java.util.concurrent.TimeUnit.SECONDS);
        timeoutTasks.put(room.getRoomId(), task);
    }

    /**
     * 取消操作超时（玩家已响应）
     */
    private void cancelActionTimeout(GameRoom room) {
        var task = timeoutTasks.remove(room.getRoomId());
        if (task != null) task.cancel(false);
    }

    /*
     当某个玩家执行了操作（碰/杠/吃/胡）后，
     通知其他等待中的玩家其可选操作已失效。
     */
    private void cancelOtherActions(GameRoom room, String actingPlayer) {
        for (String waitingPlayer : room.getWaitingForPlayers()) {
            if (waitingPlayer.equals(actingPlayer)) continue;
            Player p = room.getPlayer(waitingPlayer);
            if (p != null && p.getSessionId() != null) {
                sendToSessionId(p.getSessionId(), "ACTIONS_CANCELLED", Map.of(
                        "reason", actingPlayer + " 已执行操作"
                ));
            }
        }
        room.getWaitingForPlayers().clear();
    }

    private void advanceToNextPlayer(GameRoom room) {
        room.clearPendingActions();
        cancelActionTimeout(room);

        // 检查牌墙是否已空
        if (room.getRemainingCards() <= 0) {
            room.setState(GameRoom.State.FINISHED);
            broadcastToRoom(room, "GAME_OVER", Map.of("reason", "流局，牌墙已空"), null);
            roomManager.saveRoom(room);
            return;
        }

        // 确定当前出牌者位置：若 currentPlayerIndex == -1（刚有人出牌等待操作后），
        //    通过 lastDiscardPlayer 定位上一个出牌者，从其下家开始
        int currentPos;
        if (room.getCurrentPlayerIndex() >= 0) {
            currentPos = room.getCurrentPlayerIndex();
        } else {
            Player discarder = room.getPlayer(room.getLastDiscardPlayer());
            currentPos = discarder != null ? discarder.getPosition() : 0;
        }

        // 切换到下一个玩家
        int nextIdx = room.getNextPosition(currentPos);
        room.setCurrentPlayerIndex(nextIdx);
        Player nextPlayer = room.getCurrentPlayer();
        if (nextPlayer == null) return;

        // 摸牌
        Card drawn = room.drawCard();
        if (drawn == null) {
            room.setState(GameRoom.State.FINISHED);
            broadcastToRoom(room, "GAME_OVER", Map.of("reason", "流局"), null);
            return;
        }
        nextPlayer.addCard(drawn);

        // 通知摸牌
        broadcastToRoom(room, "DRAW", Map.of(
                "player", nextPlayer.getName(),
                "remainCards", room.getRemainingCards()
        ), null);

        // 通知该玩家摸到的牌
        if (nextPlayer.getSessionId() != null) {
            sendToSessionId(nextPlayer.getSessionId(), "YOUR_DRAW", Map.of(
                    "card", drawn.getCode(),
                    "hand", nextPlayer.getHand().stream().map(Card::getCode).toList()
            ));
        }

        // 通知所有人轮到谁
        broadcastToRoom(room, "TURN", Map.of(
                "player", nextPlayer.getName(),
                "position", nextPlayer.getPosition(),
                "remainCards", room.getRemainingCards(),
                "actionCountdown", room.getActionCountdown()
        ), null);

        roomManager.saveRoom(room);
    }

    private void setCurrentPlayer(GameRoom room, Player player) {
        for (int i = 0; i < room.getPlayers().size(); i++) {
            if (room.getPlayers().get(i).getName().equals(player.getName())) {
                room.setCurrentPlayerIndex(i);
                break;
            }
        }
        broadcastToRoom(room, "TURN", Map.of(
                "player", player.getName(),
                "position", player.getPosition(),
                "remainCards", room.getRemainingCards()
        ), null);
    }

    // 消息发送辅助

    private void sendToSession(WebSocketSession session, String type, Map<String, Object> data) {
        try {
            Map<String, Object> msg = new LinkedHashMap<>(data);
            msg.put("type", type);
            session.sendMessage(new TextMessage(mapper.writeValueAsString(msg)));
        } catch (IOException e) {
            log.error("发送消息失败: {}", e.getMessage());
        }
    }

    private void sendToSessionId(String sessionId, String type, Map<String, Object> data) {
        try {
            WebSocketSession session = wsSessionMap.get(sessionId);
            if (session == null || !session.isOpen()) return;
            Map<String, Object> msg = new LinkedHashMap<>(data);
            msg.put("type", type);
            String json = mapper.writeValueAsString(msg);
            session.sendMessage(new TextMessage(json));
        } catch (Exception e) {
            log.error("发送消息失败: {}", e.getMessage());
        }
    }

    private void broadcastToRoom(GameRoom room, String type, Map<String, Object> data, String excludePlayer) {
        Map<String, Object> msg = new LinkedHashMap<>(data);
        msg.put("type", type);
        try {
            String json = mapper.writeValueAsString(msg);
            TextMessage textMsg = new TextMessage(json);

            for (Player p : room.getPlayers()) {
                if (excludePlayer != null && p.getName().equals(excludePlayer)) continue;
                if (p.getSessionId() == null) continue;
                WebSocketSession session = wsSessionMap.get(p.getSessionId());
                if (session != null && session.isOpen()) {
                    session.sendMessage(textMsg);
                }
            }
        } catch (Exception e) {
            log.error("广播消息失败: {}", e.getMessage());
        }
    }

    private void sendError(WebSocketSession session, String errorMsg) {
        sendToSession(session, "ERROR", Map.of("message", errorMsg));
    }

    // 数据构建辅助

    private List<Map<String, Object>> buildPlayerList(GameRoom room) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Player p : room.getPlayers()) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("name", p.getName());
            info.put("position", p.getPosition());
            info.put("score", p.getScore());
            info.put("dealer", p.isDealer());
            info.put("handSize", p.getHandSize());
            info.put("meldCount", p.getMelds().size());
            list.add(info);
        }
        return list;
    }

    private List<Map<String, Object>> buildMeldList(Player player) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (MeldGroup m : player.getMelds()) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("type", m.getType().name());
            info.put("cards", m.getCards().stream().map(Card::getCode).toList());
            list.add(info);
        }
        return list;
    }
}
