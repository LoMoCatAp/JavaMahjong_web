package com.shandong.majong.controller;

import com.shandong.majong.model.GameRoom;
import com.shandong.majong.model.Player;
import com.shandong.majong.service.GameService;
import com.shandong.majong.service.RoomManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/*
 HTTP控制器 处理房间的创建、加入、查询等
 */
@RestController
@RequestMapping("/api")
public class RoomController {

    private static final Logger log = LoggerFactory.getLogger(RoomController.class);

    private final RoomManager roomManager;
    private final GameService gameService;

    public RoomController(RoomManager roomManager, GameService gameService) {
        this.roomManager = roomManager;
        this.gameService = gameService;
    }

    /*
     创建房间
     POST /api/rooms
     Body: { "playerName": "张三" }
     */
    @PostMapping("/rooms")
    public ResponseEntity<Map<String, Object>> createRoom(@RequestBody Map<String, String> body) {
        String playerName = body.getOrDefault("playerName", "").trim();
        if (playerName.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "昵称不能为空"));
        }

        GameRoom room = roomManager.createRoom(playerName);
        if (room == null) {
            return ResponseEntity.status(503).body(Map.of("error", "房间数已达上限，请稍后再试"));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("roomId", room.getRoomId());
        result.put("ownerName", playerName);
        result.put("playerCount", room.getPlayerCount());
        result.put("state", room.getState().name());
        result.put("players", buildPlayerList(room));
        return ResponseEntity.ok(result);
    }

    /*
     加入房间
     POST /api/rooms/{roomId}/join
     Body: { "playerName": "李四" }
     */
    @PostMapping("/rooms/{roomId}/join")
    public ResponseEntity<Map<String, Object>> joinRoom(
            @PathVariable String roomId,
            @RequestBody Map<String, String> body) {

        String playerName = body.getOrDefault("playerName", "").trim();
        if (playerName.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "昵称不能为空"));
        }

        GameRoom room = roomManager.joinRoom(roomId, playerName);
        if (room == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "加入失败：房间不存在、已满或正在游戏中"));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("roomId", room.getRoomId());
        result.put("playerName", playerName);
        result.put("playerCount", room.getPlayerCount());
        result.put("state", room.getState().name());
        result.put("players", buildPlayerList(room));
        return ResponseEntity.ok(result);
    }

    /*
     获取房间信息
     GET /api/rooms/{roomId}
     */
    @GetMapping("/rooms/{roomId}")
    public ResponseEntity<Map<String, Object>> getRoom(@PathVariable String roomId) {
        GameRoom room = roomManager.getRoom(roomId);
        if (room == null) {
            return ResponseEntity.notFound().build();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("roomId", room.getRoomId());
        result.put("state", room.getState().name());
        result.put("playerCount", room.getPlayerCount());
        result.put("players", buildPlayerList(room));
        return ResponseEntity.ok(result);
    }

    /*
     获取所有活跃房间
     GET /api/rooms
     */
    @GetMapping("/rooms")
    public ResponseEntity<List<Map<String, Object>>> listRooms() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (GameRoom room : roomManager.getAllRooms()) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("roomId", room.getRoomId());
            info.put("state", room.getState().name());
            info.put("playerCount", room.getPlayerCount());
            info.put("maxPlayers", GameRoom.MAX_PLAYERS);
            list.add(info);
        }
        return ResponseEntity.ok(list);
    }

    /**
     * 运行自动测试
     * POST /api/test?games=100
     */
    @PostMapping("/test")
    public ResponseEntity<Map<String, Object>> runTest(@RequestParam(defaultValue = "100") int games) {
        if (games > 10000000) games = 10000000;
        log.info("开始自动测试: {} 局", games);
        Map<String, Object> stats = gameService.testAutoPlay(games);
        return ResponseEntity.ok(stats);
    }

    /**
     * 服务健康检查
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "rooms", roomManager.getRoomCount(),
                "timestamp", System.currentTimeMillis()
        ));
    }

    // ==================== 辅助方法 ====================

    private List<Map<String, Object>> buildPlayerList(GameRoom room) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Player p : room.getPlayers()) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("name", p.getName());
            info.put("position", p.getPosition());
            info.put("score", p.getScore());
            info.put("dealer", p.isDealer());
            info.put("ready", p.isReady());
            info.put("ai", p.isAi());
            list.add(info);
        }
        return list;
    }
}
