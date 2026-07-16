package com.shandong.majong.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Random;

import com.shandong.majong.model.Card;

/*牌工具类： 
创建牌墙
牌组排序 
编码转换
手牌展示格式化*/
public final class CardUtil {

    private CardUtil() {
    } // 禁止实例化

    /*创建一副完整的山东麻将牌（136张） 万(1-9) × 4 + 条(1-9) × 4 + 筒(1-9) × 4 + 风(4种) × 4 +*/
    public static List<Card> createFullDeck() {
        List<Card> deck = new ArrayList<>(Card.TOTAL_CARDS);

        // 万: 1万~9万，各4张
        for (int v = 1; v <= 9; v++) {
            for (int i = 0; i < Card.PER_TYPE_COUNT; i++) {
                deck.add(new Card(Card.Suit.WAN, v));
            }
        }

        // 条: 1条~9条，各4张
        for (int v = 1; v <= 9; v++) {
            for (int i = 0; i < Card.PER_TYPE_COUNT; i++) {
                deck.add(new Card(Card.Suit.TIAO, v));
            }
        }

        // 筒: 1筒~9筒，各4张
        for (int v = 1; v <= 9; v++) {
            for (int i = 0; i < Card.PER_TYPE_COUNT; i++) {
                deck.add(new Card(Card.Suit.TONG, v));
            }
        }

        // 风: 东南西北，各4张
        for (int v = 1; v <= 4; v++) {
            for (int i = 0; i < Card.PER_TYPE_COUNT; i++) {
                deck.add(new Card(Card.Suit.FENG, v));
            }
        }

        // 箭: 中发白，各4张
        for (int v = 1; v <= 3; v++) {
            for (int i = 0; i < Card.PER_TYPE_COUNT; i++) {
                deck.add(new Card(Card.Suit.JIAN, v));
            }
        }

        return deck;
    }

    /*洗牌*/
    public static void shuffle(List<Card> deck) {
        Random rng = new Random();
        for (int i = deck.size() - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            // 交换 i 和 j
            Card temp = deck.get(i);
            deck.set(i, deck.get(j));
            deck.set(j, temp);
        }
    }

    /*将手牌列表转换为 34 维计数数组 索引 0-8 = 万, 9-17 = 条, 18-26 = 筒, 27-30 = 风,*/
    public static int[] toCountArray(Collection<Card> cards) {
        int[] counts = new int[Card.TOTAL_TYPES];
        for (Card c : cards) {
            counts[c.getId()]++;
        }
        return counts;
    }

    /*将计数数组转换回牌列表*/
    public static List<Card> fromCountArray(int[] counts) {
        List<Card> cards = new ArrayList<>();
        for (int i = 0; i < counts.length; i++) {
            for (int j = 0; j < counts[i]; j++) {
                cards.add(new Card(i));
            }
        }
        return cards;
    }

    /*对牌列表进行排序（按花色和数值）*/
    public static void sortCards(List<Card> cards) {
        cards.sort(null);
    }

    /*格式化手牌为字符串（用于日志输出）*/
    public static String formatHand(List<Card> hand) {
        if (hand == null || hand.isEmpty()) {
            return "空";
        }

        List<Card> sorted = new ArrayList<>(hand);
        sortCards(sorted);

        StringBuilder sb = new StringBuilder();
        Card.Suit currentSuit = null;

        for (int i = 0; i < sorted.size(); i++) {
            Card c = sorted.get(i);
            if (c.getSuit() != currentSuit) {
                if (currentSuit != null) {
                    sb.append(" | ");
                }
                currentSuit = c.getSuit();
            } else if (i > 0) {
                sb.append(" ");
            }
            sb.append(c.getDisplayName());
        }

        return sb.toString();
    }

    /*判断一组牌（3张）是否可以组成顺子 仅万、条、筒可以组成顺子，风牌和箭牌不能*/
    public static boolean isSequence(Card a, Card b, Card c) {
        if (!a.isSuited() || !b.isSuited() || !c.isSuited()) {
            return false;
        }
        if (a.getSuit() != b.getSuit() || b.getSuit() != c.getSuit()) {
            return false;
        }

        // 排序后检查是否连续
        int[] values = {a.getValue(), b.getValue(), c.getValue()};
        Arrays.sort(values);
        return values[0] + 1 == values[1] && values[1] + 1 == values[2];
    }

    /*判断一组牌（3张或4张）是否可以组成刻子*/
    public static boolean isTriplet(List<Card> cards) {
        if (cards == null || cards.size() < 3) {
            return false;
        }
        Card first = cards.get(0);
        return cards.stream().allMatch(c -> c.equals(first));
    }

    /*获取手牌中所有的可能将牌*/
    public static List<Integer> findPossiblePairs(int[] counts) {
        List<Integer> pairs = new ArrayList<>();
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] >= 2) {
                pairs.add(i);
            }
        }
        return pairs;
    }

    /*获取没有的牌（用于听牌检测） 对于每种牌（最多4张），如果手牌中不满4张，就可以听这张牌*/
    public static List<Integer> getMissingCards(int[] counts) {
        List<Integer> missing = new ArrayList<>();
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] < Card.PER_TYPE_COUNT) {
                missing.add(i);
            }
        }
        return missing;
    }
}
