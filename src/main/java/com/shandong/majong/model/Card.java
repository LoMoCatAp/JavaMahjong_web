package com.shandong.majong.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/*
 山东麻将牌牌型定义（共34种，136张）

 花色：
   WAN  - 万 (1万~9万)
   TIAO - 条 (1条~9条)
  TONG - 筒 (1筒~9筒)
   FENG - 风 (1=东, 2=南, 3=西, 4=北)
   JIAN - 箭 (1=中, 2=发, 3=白)
 
 内部编码（0-33），用于快速索引：
  0-8:   1万~9万
  9-17:  1条~9条
   18-26: 1筒~9筒
   27-30: 东南西北
   31-33: 中发白
 */
public class Card implements Comparable<Card> {

    /* 花色枚举 */
    public enum Suit {
        WAN("万"), TIAO("条"), TONG("筒"), FENG("风"), JIAN("箭");

        private final String chinese;
        Suit(String chinese) { this.chinese = chinese; }
        public String getChinese() { return chinese; }
    }

    /*花色 */
    private final Suit suit;

    /*
     牌面数值
     万/条/筒: 1~9
     风: 1=东, 2=南, 3=西, 4=北
     箭: 1=中, 2=发, 3=白
     */
    private final int value;

    /* 算法对应id (0-33) */
    private final int id;

    // 常量定义

    /* 总牌种类数 */
    public static final int TOTAL_TYPES = 34;
    /*每种牌的数量 */
    public static final int PER_TYPE_COUNT = 4;
    /* 总牌数 */
    public static final int TOTAL_CARDS = 136;
    /*万/条/筒各9种 */
    public static final int SUITED_PER_SUIT = 9;

    // 构造函数

    /*根据花色和数值创建牌*/
    public Card(Suit suit, int value) {
        this.suit = suit;
        this.value = value;
        this.id = encodeId(suit, value);
    }

    /*根据内部编码创建牌*/
    public Card(int id) {
        if (id < 0 || id >= TOTAL_TYPES) {
            throw new IllegalArgumentException("Card id must be 0-33, got: " + id);
        }
        this.id = id;
        if (id <= 8) {
            this.suit = Suit.WAN;
            this.value = id + 1;
        } else if (id <= 17) {
            this.suit = Suit.TIAO;
            this.value = id - 9 + 1;
        } else if (id <= 26) {
            this.suit = Suit.TONG;
            this.value = id - 18 + 1;
        } else if (id <= 30) {
            this.suit = Suit.FENG;
            this.value = id - 27 + 1;
        } else {
            this.suit = Suit.JIAN;
            this.value = id - 31 + 1;
        }
    }

    // 静态工厂方法

    /* 根据内部编码计算花色和数值 */
    private static int encodeId(Suit suit, int value) {
        switch (suit) {
            case WAN:  return value - 1;
            case TIAO: return 9 + value - 1;
            case TONG: return 18 + value - 1;
            case FENG: return 27 + value - 1;
            case JIAN: return 31 + value - 1;
            default: throw new IllegalArgumentException("Unknown suit: " + suit);
        }
    }

    // Getters

    public Suit getSuit() { return suit; }
    public int getValue() { return value; }

    /*获取内部编码 (0-33)，用于胡牌/听牌算法中的快速索引
     */
    public int getId() { return id; }

    /*
     获取牌的显示名称，如 "1万", "东", "中"
     */
    public String getDisplayName() {
        switch (suit) {
            case WAN:  return value + "万";
            case TIAO: return value + "条";
            case TONG: return value + "筒";
            case FENG:
                switch (value) {
                    case 1: return "东";
                    case 2: return "南";
                    case 3: return "西";
                    case 4: return "北";
                }
            case JIAN:
                switch (value) {
                    case 1: return "中";
                    case 2: return "发";
                    case 3: return "白";
                }
        }
        return "?";
    }

    /*
      获取简短编码，用于 WebSocket 消息传输，如 "1W", "DONG"
     */
    @JsonValue
    public String getCode() {
        switch (suit) {
            case WAN:  return value + "W";
            case TIAO: return value + "T";
            case TONG: return value + "B";
            case FENG:
                switch (value) {
                    case 1: return "DONG";
                    case 2: return "NAN";
                    case 3: return "XI";
                    case 4: return "BEI";
                }
            case JIAN:
                switch (value) {
                    case 1: return "ZHONG";
                    case 2: return "FA";
                    case 3: return "BAI";
                }
        }
        return "?";
    }

    /*
      从简短编码创建牌（用于 WebSocket 消息解析）
     */
    @JsonCreator
    public static Card fromCode(String code) {
        if (code == null || code.isEmpty()) return null;
        code = code.toUpperCase();
        switch (code) {
            case "DONG": return new Card(Suit.FENG, 1);
            case "NAN":  return new Card(Suit.FENG, 2);
            case "XI":   return new Card(Suit.FENG, 3);
            case "BEI":  return new Card(Suit.FENG, 4);
            case "ZHONG": return new Card(Suit.JIAN, 1);
            case "FA":   return new Card(Suit.JIAN, 2);
            case "BAI":  return new Card(Suit.JIAN, 3);
        }
        // 解析带后缀的牌 (如 "3W", "5T", "8B")
        if (code.length() >= 2) {
            try {
                int v = Integer.parseInt(code.substring(0, code.length() - 1));
                char s = code.charAt(code.length() - 1);
                switch (s) {
                    case 'W': return new Card(Suit.WAN, v);
                    case 'T': return new Card(Suit.TIAO, v);
                    case 'B': return new Card(Suit.TONG, v);
                }
            } catch (NumberFormatException ignored) {}
        }
        throw new IllegalArgumentException("Invalid card code: " + code);
    }

    /*
      判断是否是有序数牌（万、条、筒），可以组成顺子
     */
    public boolean isSuited() {
        return suit == Suit.WAN || suit == Suit.TIAO || suit == Suit.TONG;
    }

    /*
      判断是否是风牌或箭牌（不能组成顺子，只能组成刻子）
     */
    public boolean isHonor() {
        return suit == Suit.FENG || suit == Suit.JIAN;
    }

    // equals / hashCode / toString

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Card)) return false;
        Card other = (Card) o;
        return this.id == other.id;
    }

    @Override
    public int hashCode() {
        return Integer.hashCode(id);
    }

    @Override
    public int compareTo(Card other) {
        return Integer.compare(this.id, other.id);
    }

    @Override
    public String toString() {
        return getDisplayName();
    }
}
