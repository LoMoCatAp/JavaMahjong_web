package com.shandong.majong.model;

import java.util.ArrayList;
import java.util.List;

/*
  副露组（碰/杠/吃后的明牌组合）

  例如：
    碰 3万:  type=PENG, cards=[3W,3W,3W]
    明杠 5条: type=GANG_MING, cards=[5T,5T,5T,5T]
    暗杠 中:  type=GANG_AN, cards=[中,中,中,中]
    吃 4万:  type=CHI, cards=[3W,4W,5W], fromPlayer=上家
 */
public class MeldGroup {

    /* 副露类型 */
    public enum Type {
        PENG("碰"),       // 碰：其他玩家打出，手中有2张相同
        GANG_MING("明杠"), // 明杠：其他玩家打出，手中有3张相同
        GANG_AN("暗杠"),   // 暗杠：自己摸到4张相同
        GANG_BU("补杠"),   // 补杠：碰后再摸到第4张
        CHI("吃")         // 吃：上家打出，手中2张可组成顺子
        ;

        private final String chinese;
        Type(String chinese) { this.chinese = chinese; }
        public String getChinese() { return chinese; }
    }

    /* 副露类型 */
    private Type type;

    /* 副露包含的牌 */
    private List<Card> cards = new ArrayList<>();

    /* 提供被碰/杠/吃牌的那个玩家的名字（明杠/碰/吃时需要） */
    private String fromPlayer;

    // 构造函数

    public MeldGroup() {}

    public MeldGroup(Type type, List<Card> cards, String fromPlayer) {
        this.type = type;
        this.cards = cards;
        this.fromPlayer = fromPlayer;
    }

    // Getters/Setters

    public Type getType() { return type; }
    public void setType(Type type) { this.type = type; }

    public List<Card> getCards() { return cards; }
    public void setCards(List<Card> cards) { this.cards = cards; }

    public String getFromPlayer() { return fromPlayer; }
    public void setFromPlayer(String fromPlayer) { this.fromPlayer = fromPlayer; }

    @Override
    public String toString() {
        return type.getChinese() + " " + cards;
    }
}
