package com.example.ragbilibili.util;

/**
 * 向量ID生成工具类
 *
 * 向量ID格式：user_id_bvid_chunk_index
 * 示例：1001_BV1DCfsBKExV_2
 */
public class VectorIDGenerator {
    /**
     * 生成向量ID
     *
     * @param userId 用户ID
     * @param bvid BV号
     * @param chunkIndex 分片序号
     * @return 向量ID
     */
    public static String generate(Long userId, String bvid, Integer chunkIndex) {
        return String.format("%d_%s_%d", userId, bvid, chunkIndex);
    }

    /**
     * 解析向量ID
     *
     * @param vectorId 向量ID
     * @return [user_id, bvid, chunk_index]
     */
    public static String[] parse(String vectorId) {
        return vectorId.split("_", 3);
    }
}
