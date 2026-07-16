package com.shandong.majong.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.ArrayList;
import java.util.List;

/*
  玩家实体类

  包含玩家的基本信息、手牌、副露（碰/杠/吃）、出牌记录和 WebSocket 会话引用。
 */
public class Player {

    /* 玩家昵称 */
    private String name;
    /* 座位位置 (0=庄家, 1=南, 2=西, 3=北) */
    private int position;
    /* 当前积分 */
    private int score;
    /* 是否已准备 */
    private boolean ready;
    /* 是否是庄家 */
    private boolean dealer;
    /* 是否是 AI 机器人 */
    private boolean ai;

    //  牌相关

    /* 手牌列表 */
    private final List<Card> hand = new ArrayList<>();

    /* 副露列表（碰、杠、吃的牌组） */
    private final List<MeldGroup> melds = new ArrayList<>();

    /* 已打出的牌（弃牌堆，仅记录最近几轮） */
    private final List<Card> discarded = new ArrayList<>();

    // WebSocket会话

    /* WebSocket 会话 ID（用于消息推送，不序列化到 JSON） */
    @JsonIgnore
    private transient String sessionId;

    // 构造函数

    public Player() {}

    public Player(String name, int position) {
        this.name = name;
        this.position = position;
        this.score = 0;
        this.ready = false;
        this.dealer = (position == 0);
        this.ai = false;
    }

    // 手牌操作

    /*
     * 向手牌中添加一张牌（自动排序）
     */
    public void addCard(Card card) {
        hand.add(card);
        sortHand();
    }

    /*
     * 从手牌中移除一张牌
     * @return 是否移除成功
     */
    public boolean removeCard(Card card) {
        return hand.remove(card);
    }

    /*
     * 从手牌中移除指定位置的牌
     */
    public Card removeCardAt(int index) {
        return hand.remove(index);
    }

    /*
     * 手牌排序（按花色和数值，方便玩家查看）
     */
    public void sortHand() {
        hand.sort(null); // 使用 Card.compareTo（按 id 排序）
    }

    /*
     * 清空手牌
     */
    public void clearHand() {
        hand.clear();
        melds.clear();
        discarded.clear();
    }

    // 副露操作

    /*
     * 添加一个副露组（碰/杠/吃）
     */
    public void addMeld(MeldGroup meld) {
        melds.add(meld);
    }

    /*
     * 获取副露中所有牌的总数（用于计算总牌数）
     */
    @JsonIgnore
    public int getMeldCardCount() {
        return melds.stream().mapToInt(m -> m.getCards().size()).sum();
    }

    // 弃牌操作

    /*
     * 记录弃牌
     */
    public void discardCard(Card card) {
        discarded.add(card);
    }

    // Getters / Setters

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }

    public int getScore() { return score; }
    public void setScore(int score) { this.score = score; }
    public void addScore(int delta) { this.score += delta; }

    public boolean isReady() { return ready; }
    public void setReady(boolean ready) { this.ready = ready; }

    public boolean isDealer() { return dealer; }
    public void setDealer(boolean dealer) { this.dealer = dealer; }

    public boolean isAi() { return ai; }
    public void setAi(boolean ai) { this.ai = ai; }

    public List<Card> getHand() { return hand; }
    public List<MeldGroup> getMelds() { return melds; }
    public List<Card> getDiscarded() { return discarded; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    /*
     * 获取手牌数量
     */
    @JsonIgnore
    public int getHandSize() {
        return hand.size();
    }

    @Override
    public String toString() {
        return "Player{name='" + name + "', pos=" + position
                + ", hand=" + hand.size() + "张, score=" + score + "}";
    }
}
