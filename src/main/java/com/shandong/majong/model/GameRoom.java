package com.shandong.majong.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/*
 游戏房间实体类 —— 管理一场麻将对局的全部状态

 核心职责：
  1. 维护房间元数据（房间号、创建时间、状态机）
  2. 管理玩家列表（最多4人）
  3. 管理牌墙（136张牌的洗牌和发牌）
  4. 追踪游戏进程（当前出牌者、弃牌堆、待处理操作）

  状态机流转：
  WAITING → READY → PLAYING → FINISHED
  FINISHED 后可以重置回到 WAITING（重新开局）
 */
public class GameRoom {

    // ==================== 状态机 ====================

    /* 房间状态 */
    public enum State {
        WAITING,  // 等待玩家加入
        READY,    // 人满/准备完毕，等待开始
        PLAYING,  // 游戏中
        FINISHED  // 游戏结束
    }

    /* 最大玩家数 */
    public static final int MAX_PLAYERS = 4;

    // ==================== 房间元数据 ====================

    /* 房间号（6位大写字母+数字，如 #ABC123） */
    private String roomId;

    /* 房间创建时间 */
    private Instant createTime;

    /* 当前状态 */
    private State state;

    /* 房主（创建者）的名字 */
    private String ownerName;

    // 玩家管理

    /* 玩家列表（使用并发安全的 CopyOnWriteArrayList） */
    private final List<Player> players = new CopyOnWriteArrayList<>();

    // 牌墙与牌局

    /* 牌墙（136张牌洗牌后的序列） */
    private final List<Card> wall = new ArrayList<>();

    /* 牌墙当前索引（下一张要摸的牌） */
    private int wallIndex;

    /* 当前出牌玩家的位置索引 (0-3) */
    private int currentPlayerIndex;

    /* 最近一次被弃出的牌（用于碰/杠/吃/胡的判定） */
    private Card lastDiscard;

    /* 最近一次弃牌的玩家名字 */
    private String lastDiscardPlayer;

    /* 当前待处理的操作列表（出牌后等待其他玩家碰/杠/吃/胡） */
    private final List<PendingAction> pendingActions = new ArrayList<>();

    /* 操作倒计时（秒） */
    private int actionCountdown;

    /* 当前轮的操作用户（等待其做出响应） */
    @JsonIgnore
    private transient final Set<String> waitingForPlayers = ConcurrentHashMap.newKeySet();

    /* 操作优先级: 胡 > 杠 > 碰 > 吃 */
    public enum ActionType {
        HU(4), GANG(3), PENG(2), CHI(1), PASS(0);

        private final int priority;
        ActionType(int priority) { this.priority = priority; }
        public int getPriority() { return priority; }
    }

    /* 待处理的操作 */
    public static class PendingAction {
        private String playerName;
        private ActionType actionType;
        private Card card;
        private String target; // 吃时的目标组合，如 "3W_5W"

        public PendingAction() {}
        public PendingAction(String playerName, ActionType actionType, Card card) {
            this.playerName = playerName;
            this.actionType = actionType;
            this.card = card;
        }

        public String getPlayerName() { return playerName; }
        public void setPlayerName(String playerName) { this.playerName = playerName; }
        public ActionType getActionType() { return actionType; }
        public void setActionType(ActionType actionType) { this.actionType = actionType; }
        public Card getCard() { return card; }
        public void setCard(Card card) { this.card = card; }
        public String getTarget() { return target; }
        public void setTarget(String target) { this.target = target; }
    }

    // 构造函数

    public GameRoom() {}

    public GameRoom(String roomId, String ownerName) {
        this.roomId = roomId;
        this.ownerName = ownerName;
        this.createTime = Instant.now();
        this.state = State.WAITING;
        this.currentPlayerIndex = 0;
        this.wallIndex = 0;
    }

    // 玩家管理方法

    /*
     * 添加玩家到房间
     */
    public synchronized boolean addPlayer(Player player) {
        if (players.size() >= MAX_PLAYERS) return false;
        if (getPlayer(player.getName()) != null) return false;

        player.setPosition(players.size());
        players.add(player);
        return true;
    }

    /*
     * 移除玩家
     */
    public synchronized boolean removePlayer(String name) {
        return players.removeIf(p -> p.getName().equals(name));
    }

    /*
     * 根据名字查找玩家
     */
    public Player getPlayer(String name) {
        return players.stream()
                .filter(p -> p.getName().equals(name))
                .findFirst().orElse(null);
    }

    /*
     * 根据座位位置查找玩家
     */
    public Player getPlayerByPosition(int position) {
        return players.stream()
                .filter(p -> p.getPosition() == position)
                .findFirst().orElse(null);
    }

    /*
     * 获取当前出牌玩家
     */
    @JsonIgnore
    public Player getCurrentPlayer() {
        if (currentPlayerIndex < 0 || currentPlayerIndex >= players.size()) return null;
        return players.get(currentPlayerIndex);
    }

    /*
     * 获取庄家（位置0的玩家）
     */
    @JsonIgnore
    public Player getDealer() {
        return getPlayerByPosition(0);
    }

    /*
     * 获取下一个位置（用于轮流）
     */
    public int getNextPosition(int currentPos) {
        return (currentPos + 1) % players.size();
    }

    /*
     * 获取前一个位置
     */
    public int getPrevPosition(int currentPos) {
        return (currentPos - 1 + players.size()) % players.size();
    }

    // 牌墙操作

    /*
     * 从牌墙摸一张牌
     * @return 摸到的牌，若牌墙已空返回 null
     */
    public Card drawCard() {
        if (wallIndex >= wall.size()) return null;
        return wall.get(wallIndex++);
    }

    /*
     * 牌墙剩余牌数
     */
    @JsonIgnore
    public int getRemainingCards() {
        return wall.size() - wallIndex;
    }

    // 待处理操作

    /*
     * 清空待处理操作
     */
    public void clearPendingActions() {
        pendingActions.clear();
        waitingForPlayers.clear();
        actionCountdown = 0;
    }

    /*
     * 添加待处理操作
     */
    public void addPendingAction(PendingAction action) {
        pendingActions.add(action);
    }

    /*
     * 添加等待响应的玩家
     */
    public void addWaitingPlayer(String playerName) {
        waitingForPlayers.add(playerName);
    }

    /*
     * 移除等待响应的玩家
     */
    public void removeWaitingPlayer(String playerName) {
        waitingForPlayers.remove(playerName);
    }

    /*
     * 所有等待的玩家是否都已响应
     */
    @JsonIgnore
    public boolean isAllResponded() {
        return waitingForPlayers.isEmpty();
    }

    /*
     * 获取等待中的玩家集合
     */
    @JsonIgnore
    public Set<String> getWaitingForPlayers() {
        return waitingForPlayers;
    }

    /*
     * 获取最高优先级的待处理操作
     */
    @JsonIgnore
    public PendingAction getBestPendingAction() {
        return pendingActions.stream()
                .filter(a -> a.getActionType() != ActionType.PASS)
                .max(Comparator.comparingInt(a -> a.getActionType().getPriority()))
                .orElse(null);
    }

    // 状态切换

    /*
     * 检查房间是否可以开始游戏（至少2人，所有人准备）
     */
    public boolean canStart() {
        if (players.size() < 2) return false;
        if (state != State.WAITING && state != State.READY) return false;
        return players.stream().allMatch(Player::isReady);
    }

    // Getters / Setters

    public String getRoomId() { return roomId; }
    public void setRoomId(String roomId) { this.roomId = roomId; }

    public Instant getCreateTime() { return createTime; }
    public void setCreateTime(Instant createTime) { this.createTime = createTime; }

    public State getState() { return state; }
    public void setState(State state) { this.state = state; }

    public String getOwnerName() { return ownerName; }
    public void setOwnerName(String ownerName) { this.ownerName = ownerName; }

    public List<Player> getPlayers() { return players; }

    public List<Card> getWall() { return wall; }
    public int getWallIndex() { return wallIndex; }
    public void setWallIndex(int wallIndex) { this.wallIndex = wallIndex; }

    public int getCurrentPlayerIndex() { return currentPlayerIndex; }
    public void setCurrentPlayerIndex(int currentPlayerIndex) { this.currentPlayerIndex = currentPlayerIndex; }

    public Card getLastDiscard() { return lastDiscard; }
    public void setLastDiscard(Card lastDiscard) { this.lastDiscard = lastDiscard; }

    public String getLastDiscardPlayer() { return lastDiscardPlayer; }
    public void setLastDiscardPlayer(String lastDiscardPlayer) { this.lastDiscardPlayer = lastDiscardPlayer; }

    public List<PendingAction> getPendingActions() { return pendingActions; }
    public int getActionCountdown() { return actionCountdown; }
    public void setActionCountdown(int actionCountdown) { this.actionCountdown = actionCountdown; }

    @JsonIgnore
    public int getPlayerCount() { return players.size(); }

    @Override
    public String toString() {
        return "GameRoom{" + roomId + ", state=" + state
                + ", players=" + players.size() + "/" + MAX_PLAYERS
                + ", wall=" + getRemainingCards() + " remaining}";
    }
}
