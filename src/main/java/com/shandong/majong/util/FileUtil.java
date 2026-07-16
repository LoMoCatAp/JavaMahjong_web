package com.shandong.majong.util;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/*
 文件读写工具类
 使用 Jackson 进行 JSON 序列化/反序列化。
 数据文件存储在配置的mahjong.data-dir目录，
 每个房间对应一个 JSON 文件。
 */
public final class FileUtil {

    private static final Logger log = LoggerFactory.getLogger(FileUtil.class);

    /* Jackson 对象映射器 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /* 数据存储根目录 */
    private static String dataDir = "./data/rooms";

    static {
        // 注册 Java 8 时间模块
        MAPPER.registerModule(new JavaTimeModule());
        // 关闭将日期写为时间戳
        MAPPER.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        // 启用美化输出
        MAPPER.enable(SerializationFeature.INDENT_OUTPUT);
    }

    private FileUtil() {}

    /*
      设置数据存储目录
     */
    public static void setDataDir(String dir) {
        dataDir = dir;
    }

    /*
      获取 ObjectMapper（供其他地方使用）
     */
    public static ObjectMapper getMapper() {
        return MAPPER;
    }

    /*
      确保数据目录存在
     */
    public static void ensureDataDir() {
        try {
            Files.createDirectories(Paths.get(dataDir));
        } catch (IOException e) {
            log.error("无法创建数据目录: {}", dataDir, e);
        }
    }

    /*
      将对象序列化为 JSON 并保存到文件

      @param obj      要保存的对象
      @param filePath 相对于 dataDir 的文件名
     */
    public static void saveToFile(Object obj, String filePath) {
        ensureDataDir();
        Path fullPath = Paths.get(dataDir, filePath);
        try {
            MAPPER.writeValue(fullPath.toFile(), obj);
            log.debug("数据已保存: {}", fullPath);
        } catch (IOException e) {
            log.error("保存文件失败: {}", fullPath, e);
        }
    }

    /*
      从文件读取 JSON 并反序列化为指定类型
     */
    public static <T> T loadFromFile(String filePath, Class<T> clazz) {
        Path fullPath = Paths.get(dataDir, filePath);
        File file = fullPath.toFile();
        if (!file.exists()) {
            log.debug("文件不存在: {}", fullPath);
            return null;
        }
        try {
            T obj = MAPPER.readValue(file, clazz);
            log.debug("数据已加载: {}", fullPath);
            return obj;
        } catch (IOException e) {
            log.error("加载文件失败: {}", fullPath, e);
            return null;
        }
    }

    /*
     删除文件
     */
    public static void deleteFile(String filePath) {
        Path fullPath = Paths.get(dataDir, filePath);
        try {
            Files.deleteIfExists(fullPath);
            log.debug("文件已删除: {}", fullPath);
        } catch (IOException e) {
            log.error("删除文件失败: {}", fullPath, e);
        }
    }

    /*
     列出指定目录下的所有 JSON 文件
     */
    public static String[] listRoomFiles() {
        ensureDataDir();
        File dir = new File(dataDir);
        String[] files = dir.list((d, name) -> name.endsWith(".json"));
        return files != null ? files : new String[0];
    }

    /*
     获取房间文件名
     */
    public static String getRoomFileName(String roomId) {
        return "room_" + roomId + ".json";
    }

    /*
      从文件名提取房间号
     */
    public static String extractRoomId(String fileName) {
        // room_ABC123.json -> ABC123
        if (fileName == null) return null;
        String name = fileName.replace("room_", "").replace(".json", "");
        return name.isEmpty() ? null : name;
    }
}
