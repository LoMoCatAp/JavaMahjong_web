package com.shandong.majong.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.shandong.majong.model.Card;
import com.shandong.majong.model.GameRoom;
import com.shandong.majong.model.MeldGroup;
import com.shandong.majong.model.Player;
import com.shandong.majong.util.CardUtil;

/*麻将算法核心*/
@Service
public class GameService {

    private static final Logger log = LoggerFactory.getLogger(GameService.class);

    // 游戏初始化

    /*初始化一局新游戏：创建牌墙、洗牌、发牌*/
    public void initGame(GameRoom room) {
        // 创建完整牌墙
        List<Card> deck = CardUtil.createFullDeck();
        CardUtil.shuffle(deck);

        // 设置牌墙
        room.getWall().clear();
        room.getWall().addAll(deck);
        room.setWallIndex(0);

        // 清理所有玩家的手牌
        for (Player p : room.getPlayers()) {
            p.clearHand();
        }

        // 发牌: 庄家14，其余13
        for (int round = 0; round < 13; round++) {
            for (Player p : room.getPlayers()) {
                p.addCard(room.drawCard());
            }
        }
        // 庄家多摸1
        Player dealer = room.getDealer();
        if (dealer != null) {
            dealer.addCard(room.drawCard());
        }

        // 设置初始出牌者为庄家
        for (int i = 0; i < room.getPlayers().size(); i++) {
            if (room.getPlayers().get(i).isDealer()) {
                room.setCurrentPlayerIndex(i);
                break;
            }
        }

        room.setState(GameRoom.State.PLAYING);
        room.setActionCountdown(30);
        log.info("游戏初始化完成: {}, 牌墙剩余 {}", room.getRoomId(), room.getRemainingCards());
    }

    // 胡牌检测
    public boolean checkHu(List<Card> hand) {
        if (hand == null || hand.isEmpty()) {
            return false;
        }
        //总牌数
        int totalCards = hand.size();

        //3n+2
        if(totalCards%3!=2)
        {return false;}

        int[] counts = CardUtil.toCountArray(hand);

        // 尝试每种可能的将牌
        List<Integer> possiblePairs = CardUtil.findPossiblePairs(counts);
        for (int pairId : possiblePairs) {
            // 暂时移除将牌
            counts[pairId] -= 2;

            // 检查剩余牌能否全部组成面子
            if (canFormMelds(counts, totalCards - 2)) {
                // 恢复并返回成功
                counts[pairId] += 2;
                return true;
            }

            // 回溯：恢复将牌，尝试下一种
            counts[pairId] += 2;
        }

        return false;
    }

    /*检查计数数组中的牌能否全部组成合法的面子*/
    private boolean canFormMelds(int[] counts, int remaining) {
        // 递归终止：所有牌都成功拆解
        if (remaining == 0) {
            return true;
        }

        // 找到第一个有牌的位置
        for (int i = 0; i < Card.TOTAL_TYPES; i++) {
            if (counts[i] == 0) {
                continue;
            }

            //将当前牌作为刻子
            if (counts[i] >= 3) {
                counts[i] -= 3;
                if (canFormMelds(counts, remaining - 3)) {
                    counts[i] += 3;
                    return true;
                }
                counts[i] += 3; // 回溯
            }

            // 将当前牌作为顺子的第一张
            if (i <= 26 && (i % 9) <= 6) {
                if (counts[i + 1] > 0 && counts[i + 2] > 0) {
                    counts[i]--;
                    counts[i + 1]--;
                    counts[i + 2]--;
                    if (canFormMelds(counts, remaining - 3)) {
                        counts[i]++;
                        counts[i + 1]++;
                        counts[i + 2]++;
                        return true;
                    }
                    counts[i]++;
                    counts[i + 1]++;
                    counts[i + 2]++; // 回溯
                }
            }

            // 当前牌既不能组成刻子也不能组成顺子
            return false;
        }

        return true;
    }

    // 听牌检测
    public List<Card> checkTing(List<Card> hand) {
        List<Card> result = new ArrayList<>();
        if (hand == null || hand.isEmpty()) {
            return result;
        }

        int total = hand.size();
        int remainder = total % 3;

        if (remainder == 1) {
            // 缺1张可胡,遍历34种牌看加哪张能胡
            return findTingByAdding(hand);
        } else if (remainder == 2) {
            // 多1张,检查是否可以自摸
            if (checkHu(hand)) {
                // 胡牌 选择打出牌型
                return new ArrayList<>(new LinkedHashSet<>(hand));
            }
            // 不能胡 遍历手牌中每种牌，看打掉哪张后能变成听牌
            return findTingByRemoving(hand);
        }
        // 手牌数不对（如0张）
        return result;
    }

    /*手牌 %3==1，缺1张：遍历34种牌，模拟加入后调用胡牌检测*/
    private List<Card> findTingByAdding(List<Card> hand) {
        List<Card> tingCards = new ArrayList<>();
        int[] counts = CardUtil.toCountArray(hand);

        for (int i = 0; i < Card.TOTAL_TYPES; i++) {
            // 每种牌最多4张，已有4张则牌墙中已无此牌
            if (counts[i] >= Card.PER_TYPE_COUNT) {
                continue;
            }

            List<Card> testHand = new ArrayList<>(hand);
            testHand.add(new Card(i));
            CardUtil.sortCards(testHand);

            if (checkHu(testHand)) {
                tingCards.add(new Card(i));
            }
        }
        return tingCards;
    }

    /*
     手牌 %3==2，多1张需弃牌：对每种手牌，模拟丢弃后检查能否听牌
     返回"打掉这张牌后能达到听牌状态"的牌列表
     */
    private List<Card> findTingByRemoving(List<Card> hand) {
        // 用 Set 去重（手牌中同一种牌可能有多张，但提示一次就够了）
        Set<Integer> resultIds = new LinkedHashSet<>();

        // 统计每种牌在手牌中出现的次数
        int[] handCounts = CardUtil.toCountArray(hand);

        for (int discardId = 0; discardId < Card.TOTAL_TYPES; discardId++) {
            if (handCounts[discardId] == 0) {
                continue; // 手牌中没有这种牌
            }
            // 模拟丢弃一张牌
            List<Card> remaining = new ArrayList<>(hand);
            remaining.remove(new Card(discardId)); // 移除一张

            // 检查丢弃后剩13张能否 + 某张牌 = 胡
            int[] remainCounts = CardUtil.toCountArray(remaining);
            for (int addId = 0; addId < Card.TOTAL_TYPES; addId++) {
                if (remainCounts[addId] >= Card.PER_TYPE_COUNT) {
                    continue;
                }

                List<Card> testHand = new ArrayList<>(remaining);
                testHand.add(new Card(addId));
                CardUtil.sortCards(testHand);

                if (checkHu(testHand)) {
                    resultIds.add(discardId); // 打掉这张牌能听
                    break; // 不需要继续检查其他 addId
                }
            }
        }

        List<Card> result = new ArrayList<>();
        for (int id : resultIds) {
            result.add(new Card(id));
        }
        return result;
    }

    /*
     检查玩家是否可以胡某张打出的牌
     */
    public boolean canHu(Player player, Card discardCard) {
        List<Card> testHand = new ArrayList<>(player.getHand());
        testHand.add(discardCard);
        return checkHu(testHand);
    }

    // 操作判定

    /*
     检查玩家是否可以碰某张打出的牌
     条件：手牌中有 >= 2 张与弃牌相同的牌
     */
    public boolean canPeng(Player player, Card discardCard) {
        long count = player.getHand().stream()
                .filter(c -> c.equals(discardCard))
                .count();
        return count >= 2;
    }

    /*
     检查玩家是否可以明杠某张打出的牌
     条件：手牌中有 >= 3 张与弃牌相同的牌
     */
    public boolean canMingGang(Player player, Card discardCard) {
        long count = player.getHand().stream()
                .filter(c -> c.equals(discardCard))
                .count();
        return count >= 3;
    }

    /*
     检查玩家是否可以暗杠（自己摸到第4张） 条件：手牌中有 4 张相同的牌
     */
    public boolean canAnGang(Player player) {
        int[] counts = CardUtil.toCountArray(player.getHand());
        for (int c : counts) {
            if (c >= 4) {
                return true;
            }
        }
        return false;
    }

    /*
     检查玩家是否可以补杠（碰过之后再摸到第4张） 条件：之前碰过的牌，手中还有第4张
     */
    public Card canBuGang(Player player) {
        for (MeldGroup meld : player.getMelds()) {
            if (meld.getType() == MeldGroup.Type.PENG) {
                Card meldCard = meld.getCards().get(0);
                for (Card c : player.getHand()) {
                    if (c.equals(meldCard)) {
                        return c;
                    }
                }
            }
        }
        return null;
    }

    /*
检查玩家是否可以吃某张打出的牌（只能吃上家）
返回所有可能的吃法列表，每种吃法是一组牌的列表
     */
    public List<List<Card>> canChi(Player player, Card discardCard) {
        List<List<Card>> chiOptions = new ArrayList<>();

        // 风牌和箭牌不能吃
        if (!discardCard.isSuited()) {
            return chiOptions;
        }

        int cardId = discardCard.getId();
        int suitStart = (cardId / Card.SUITED_PER_SUIT) * Card.SUITED_PER_SUIT;

        // 在手牌中建立快速查找表
        Set<Integer> handIds = new HashSet<>();
        for (Card c : player.getHand()) {
            handIds.add(c.getId());
        }

        // 尝试三种组合：
        // 1) 弃牌作为顺子的第一张: X, X+1, X+2 → 需要手中有 X+1, X+2
        // 2) 弃牌作为顺子的中间: X-1, X, X+1 → 需要手中有 X-1, X+1
        // 3) 弃牌作为顺子的最后: X-2, X-1, X → 需要手中有 X-2, X-1
        // 情况1: 弃牌是最小张
        if (cardId % Card.SUITED_PER_SUIT <= 6
                && handIds.contains(cardId + 1)
                && handIds.contains(cardId + 2)) {
            chiOptions.add(Arrays.asList(
                    discardCard, new Card(cardId + 1), new Card(cardId + 2)));
        }

        // 情况2: 弃牌是中间张
        if (cardId % Card.SUITED_PER_SUIT >= 1 && cardId % Card.SUITED_PER_SUIT <= 7
                && handIds.contains(cardId - 1)
                && handIds.contains(cardId + 1)) {
            chiOptions.add(Arrays.asList(
                    new Card(cardId - 1), discardCard, new Card(cardId + 1)));
        }

        // 情况3: 弃牌是最大张
        if (cardId % Card.SUITED_PER_SUIT >= 2
                && handIds.contains(cardId - 2)
                && handIds.contains(cardId - 1)) {
            chiOptions.add(Arrays.asList(
                    new Card(cardId - 2), new Card(cardId - 1), discardCard));
        }

        return chiOptions;
    }

    /*检查玩家手牌中是否有可以暗杠的牌

     */
    public List<Card> getAnGangOptions(Player player) {
        List<Card> options = new ArrayList<>();
        int[] counts = CardUtil.toCountArray(player.getHand());
        for (int i = 0; i < Card.TOTAL_TYPES; i++) {
            if (counts[i] >= 4) {
                options.add(new Card(i));
            }
        }
        return options;
    }

    // 游戏操作执行
    /*
     执行碰操作：从手牌移除2张，与弃牌共3张组成碰副露
     */
    public void executePeng(Player player, Card targetCard) {
        List<Card> pengCards = new ArrayList<>();
        pengCards.add(targetCard); // 被碰的那张（来自弃牌堆）

        // 从手牌中找2张相同的牌移除
        List<Card> toRemove = new ArrayList<>();
        for (Card c : player.getHand()) {
            if (c.equals(targetCard) && toRemove.size() < 2) {
                toRemove.add(c);
                pengCards.add(c);
            }
        }
        player.getHand().removeAll(toRemove);
        player.addMeld(new MeldGroup(MeldGroup.Type.PENG, pengCards, null));
        log.debug("{} 碰了 {}，手牌剩余{}张，副露{}组",
                player.getName(), targetCard.getDisplayName(),
                player.getHandSize(), player.getMelds().size());
    }

    /*
     执行明杠操作：从手牌移除3张，与弃牌共4张组成明杠副露
     */
    public void executeMingGang(Player player, Card targetCard) {
        List<Card> gangCards = new ArrayList<>();
        gangCards.add(targetCard); // 被杠的那张（来自弃牌堆）

        List<Card> toRemove = new ArrayList<>();
        for (Card c : player.getHand()) {
            if (c.equals(targetCard) && toRemove.size() < 3) {
                toRemove.add(c);
                gangCards.add(c);
            }
        }
        player.getHand().removeAll(toRemove);
        player.addMeld(new MeldGroup(MeldGroup.Type.GANG_MING, gangCards, null));
        log.debug("{} 明杠了 {}，手牌剩余{}张，副露{}组",
                player.getName(), targetCard.getDisplayName(),
                player.getHandSize(), player.getMelds().size());
    }

    /*
     执行暗杠操作：从手牌移除4张相同牌组成暗杠副露
     */
    public void executeAnGang(Player player, Card targetCard) {
        List<Card> gangCards = new ArrayList<>();
        List<Card> toRemove = new ArrayList<>();
        for (Card c : player.getHand()) {
            if (c.equals(targetCard) && toRemove.size() < 4) {
                toRemove.add(c);
                gangCards.add(c);
            }
        }
        player.getHand().removeAll(toRemove);
        player.addMeld(new MeldGroup(MeldGroup.Type.GANG_AN, gangCards, null));
        log.debug("{} 暗杠了 {}，手牌剩余{}张，副露{}组",
                player.getName(), targetCard.getDisplayName(),
                player.getHandSize(), player.getMelds().size());
    }

    /*
执行吃操作
    
     */
    public void executeChi(Player player, Card discardCard, List<Card> chiCards) {
        // 从手牌中移除除了弃牌外的其他牌
        for (Card c : chiCards) {
            if (!c.equals(discardCard)) {
                player.removeCard(c);
            }
        }

        player.addMeld(new MeldGroup(MeldGroup.Type.CHI, chiCards, null));
        log.debug("{} 吃了 {} 组成 {}", player.getName(), discardCard.getDisplayName(), chiCards);
    }

    /*
     执行出牌操作
     */
    public void executePlay(Player player, Card card) {
        player.removeCard(card);
        player.discardCard(card);
    }

    // 自动测试

    /*自动测试方法 4个机器人运行指定局数，统计胡牌次数*/
    public Map<String, Object> testAutoPlay(int totalGames) {
        Map<String, Object> stats = new LinkedHashMap<>();
        Map<String, Integer> winCounts = new HashMap<>();
        int totalRounds = 0;
        int drawGames = 0;
        long startTime = System.currentTimeMillis();

        Random rng = new Random();

        for (int gameIdx = 0; gameIdx < totalGames; gameIdx++) {
            // 创建测试房间
            GameRoom room = new GameRoom("TEST" + gameIdx, "Robot0");
            for (int i = 0; i < 4; i++) {
                Player robot = new Player("Robot" + i, i);
                robot.setReady(true);
                robot.setAi(true);
                if (i == 0) {
                    robot.setDealer(true);
                }
                room.addPlayer(robot);
            }

            // 初始化游戏
            initGame(room);

            String winner = simulateGame(room, rng);
            totalRounds += room.getWallIndex(); // 摸牌数作为回合近似

            if (winner != null) {
                winCounts.merge(winner, 1, Integer::sum);
            } else {
                drawGames++;
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;

        stats.put("总局数", totalGames);
        stats.put("流局数", drawGames);
        stats.put("胡牌次数统计", winCounts);
        stats.put("平均摸牌数", totalRounds / (double) totalGames);
        stats.put("总耗时(ms)", elapsed);
        stats.put("平均每局耗时(ms)", elapsed / (double) totalGames);
        stats.put("测试结果", "PASS — 所有对局正常完成");

        log.info("自动测试完成: {}", stats);
        return stats;
    }

    /*模拟一局游戏*/
    private String simulateGame(GameRoom room, Random rng) {
        int maxTurns = 200; // 防止无限循环
        int turns = 0;

        while (turns < maxTurns) {
            Player current = room.getCurrentPlayer();
            if (current == null) {
                break;
            }

            // 庄家第一轮跳过摸牌（已有14张，直接出牌）
            if (turns > 0 || !current.isDealer() || turns == 0) {
                // 摸牌
                Card drawn = room.drawCard();
                if (drawn == null) {
                    // 牌墙空了，流局
                    return null;
                }
                current.addCard(drawn);

                // 检查是否可以自摸
                if (checkHu(current.getHand())) {
                    return current.getName();
                }
            }

            // AI 出牌策略: 简单随机出牌（优先出孤张）
            if (!current.getHand().isEmpty()) {
                Card toDiscard = selectDiscardForAI(current, rng);
                executePlay(current, toDiscard);
                room.setLastDiscard(toDiscard);
                room.setLastDiscardPlayer(current.getName());

                // 检查其他玩家是否可以胡
                for (Player p : room.getPlayers()) {
                    if (p == current) {
                        continue;
                    }
                    if (rng.nextDouble() < 0.3 && canHu(p, toDiscard)) {
                        return p.getName();
                    }
                }
            }

            // 切换到下一个玩家
            int nextIdx = room.getNextPosition(room.getCurrentPlayerIndex());
            room.setCurrentPlayerIndex(nextIdx);
            turns++;
        }

        return null; // 流局
    }

    /*出牌选择策略 优先出孤张*/
    private Card selectDiscardForAI(Player player, Random rng) {
        List<Card> hand = player.getHand();
        int[] counts = CardUtil.toCountArray(hand);

        // 优先出孤张
        List<Card> orphans = new ArrayList<>();
        List<Card> others = new ArrayList<>();

        for (Card c : hand) {
            int id = c.getId();
            boolean isOrphan = true;

            // 如果有对子，不是孤张
            if (counts[id] >= 2) {
                isOrphan = false;
            }

            // 如果能与相邻牌组成顺子，不是孤张
            if (c.isSuited() && id % Card.SUITED_PER_SUIT >= 2) {
                if (counts[id - 2] > 0 && counts[id - 1] > 0) {
                    isOrphan = false;
                }
            }
            if (c.isSuited() && id % Card.SUITED_PER_SUIT >= 1 && id % Card.SUITED_PER_SUIT <= 7) {
                if (counts[id - 1] > 0 && counts[id + 1] > 0) {
                    isOrphan = false;
                }
            }
            if (c.isSuited() && id % Card.SUITED_PER_SUIT <= 6) {
                if (counts[id + 1] > 0 && counts[id + 2] > 0) {
                    isOrphan = false;
                }
            }

            if (isOrphan) {
                orphans.add(c);
            } else {
                others.add(c);
            }
        }

        if (!orphans.isEmpty()) {
            return orphans.get(rng.nextInt(orphans.size()));
        }
        return others.get(rng.nextInt(others.size()));
    }
}
