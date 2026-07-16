package com.shandong.majong.service;

import java.util.Collection;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.shandong.majong.model.GameRoom;
import com.shandong.majong.model.Player;
import com.shandong.majong.util.FileUtil;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/*
房间管理器:
 房间的创建、加入、离开、销毁
 房间状态的定时自动保存（每5秒）
  服务器启动时从文件恢复所有房间状态
 服务器关闭时将房间状态写入磁盘
 */
@Service
public class RoomManager {

    private static final Logger log = LoggerFactory.getLogger(RoomManager.class);

    /* 房间集合（线程安全） */
    private final ConcurrentMap<String, GameRoom> rooms = new ConcurrentHashMap<>();

    /* 定时保存任务执行器 */
    private ScheduledExecutorService saveScheduler;

    /* 最大房间数 */
    private static final int MAX_ROOMS = 50;

    /* 房间号字符集 */
    private static final String ROOM_ID_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    /* 房间号长度 */
    private static final int ROOM_ID_LENGTH = 6;

    // 生命周期管理

    /*
     * 服务启动时：加载已保存的房间状态
     */
    @PostConstruct
    public void init() {
        log.info("===== 山东麻将服务器启动 =====");

        // 从文件恢复房间
        int restored = restoreAllRooms();
        log.info("从文件恢复了 {} 个房间", restored);

        // 启动定时存档任务（每5秒保存一次）
        saveScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "room-save-thread");
            t.setDaemon(true);
            return t;
        });
        saveScheduler.scheduleWithFixedDelay(
                this::saveAllRooms, 5, 5, TimeUnit.SECONDS);

        log.info("定时存档任务已启动（间隔5秒），当前共 {} 个活跃房间", rooms.size());
    }

    /*
     * 服务关闭时：保存所有房间状态
     */
    @PreDestroy
    public void shutdown() {
        log.info("===== 麻将服务器关闭 =====");

        // 关闭定时任务
        if (saveScheduler != null) {
            saveScheduler.shutdown();
            try {
                if (!saveScheduler.awaitTermination(3, TimeUnit.SECONDS)) {
                    saveScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                saveScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        // 最终保存
        saveAllRooms();
        log.info("所有房间数据已保存，服务器安全关闭");
    }

    // 房间操作

    /* 创建新房间 */
    public synchronized GameRoom createRoom(String ownerName) {
        if (rooms.size() >= MAX_ROOMS) {
            log.warn("房间数已达上限 {}", MAX_ROOMS);
            return null;
        }

        // 生成唯一房间号
        String roomId = generateRoomId();
        GameRoom room = new GameRoom(roomId, ownerName);

        // 房主自动加入
        Player owner = new Player(ownerName, 0);
        owner.setDealer(true);
        owner.setReady(true);
        room.addPlayer(owner);

        rooms.put(roomId, room);

        // 立即保存
        saveRoom(room);

        log.info("房间 {} 创建成功，房主: {}", roomId, ownerName);
        return room;
    }

    /*加入已有房间*/
    public synchronized GameRoom joinRoom(String roomId, String playerName) {
        GameRoom room = rooms.get(roomId);
        if (room == null) {
            log.warn("房间 {} 不存在", roomId);
            return null;
        }
        if (room.getState() == GameRoom.State.PLAYING) {
            log.warn("房间 {} 正在游戏中，无法加入", roomId);
            return null;
        }
        if (room.getPlayerCount() >= GameRoom.MAX_PLAYERS) {
            log.warn("房间 {} 已满", roomId);
            return null;
        }
        // 检查昵称是否重复
        if (room.getPlayer(playerName) != null) {
            log.warn("玩家 {} 已在房间 {} 中", playerName, roomId);
            return null;
        }

        Player player = new Player(playerName, room.getPlayerCount());
        player.setReady(true);
        boolean added = room.addPlayer(player);

        if (added) {
            log.info("玩家 {} 加入了房间 {}", playerName, roomId);
            if (room.getPlayerCount() >= GameRoom.MAX_PLAYERS) {
                room.setState(GameRoom.State.READY);
            }
            saveRoom(room);
            return room;
        }

        return null;
    }

    /*离开房间*/
    public synchronized GameRoom leaveRoom(String roomId, String playerName) {
        GameRoom room = rooms.get(roomId);
        if (room == null) return null;

        room.removePlayer(playerName);
        log.info("玩家 {} 离开了房间 {}", playerName, roomId);

        // 若房间为空则删除
        if (room.getPlayerCount() == 0) {
            rooms.remove(roomId);
            FileUtil.deleteFile(FileUtil.getRoomFileName(roomId));
            log.info("房间 {} 已空，自动销毁", roomId);
            return null;
        }

        // 更新房主
        if (room.getOwnerName().equals(playerName) && !room.getPlayers().isEmpty()) {
            room.setOwnerName(room.getPlayers().get(0).getName());
            log.info("房间 {} 房主变更为 {}", roomId, room.getOwnerName());
        }

        saveRoom(room);
        return room;
    }

    /*
     * 根据房间号查询房间
     */
    public GameRoom getRoom(String roomId) {
        return rooms.get(roomId);
    }

    /*
     * 获取所有房间列表
     */
    public Collection<GameRoom> getAllRooms() {
        return rooms.values();
    }

    /*
     * 强制销毁房间
     */
    public synchronized void destroyRoom(String roomId) {
        rooms.remove(roomId);
        FileUtil.deleteFile(FileUtil.getRoomFileName(roomId));
        log.info("房间 {} 已强制销毁", roomId);
    }

    // 持久化

    /*
     * 保存单个房间到文件
     */
    public void saveRoom(GameRoom room) {
        String fileName = FileUtil.getRoomFileName(room.getRoomId());
        FileUtil.saveToFile(room, fileName);
    }

    /*
     * 保存所有房间
     */
    private void saveAllRooms() {
        for (GameRoom room : rooms.values()) {
            saveRoom(room);
        }
        log.debug("已保存 {} 个房间的状态", rooms.size());
    }

    /*从文件恢复所有房间状态*/
    private int restoreAllRooms() {
        String[] files = FileUtil.listRoomFiles();
        int restored = 0;

        for (String file : files) {
            GameRoom room = FileUtil.loadFromFile(file, GameRoom.class);
            if (room != null && room.getRoomId() != null) {
                rooms.put(room.getRoomId(), room);
                restored++;
                log.info("恢复房间: {} ({} 名玩家, 状态: {})",
                        room.getRoomId(), room.getPlayerCount(), room.getState());
            }
        }

        return restored;
    }


    /*生成6位房间号*/
    private String generateRoomId() {
        Random rng = new Random();
        String roomId;
        int attempts = 0;

        do {
            StringBuilder sb = new StringBuilder(ROOM_ID_LENGTH);
            for (int i = 0; i < ROOM_ID_LENGTH; i++) {
                sb.append(ROOM_ID_CHARS.charAt(rng.nextInt(ROOM_ID_CHARS.length())));
            }
            roomId = sb.toString();
            attempts++;
        } while (rooms.containsKey(roomId) && attempts < 100);

        return roomId;
    }

    /*获取房间总数*/
    public int getRoomCount() {
        return rooms.size();
    }
}
