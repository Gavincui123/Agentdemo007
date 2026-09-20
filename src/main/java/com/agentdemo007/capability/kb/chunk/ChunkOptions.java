package com.agentdemo007.capability.kb.chunk;

/**
 * 切块参数（[[kb-ingest-design]]·任务2）。
 *
 * @param maxChars 单块字符上限（通用递归切块硬上限；条款切块的"条款簇"打包上限）
 * @param overlap  超长段落递归切分的重叠窗口（跨块语义连续性；条款切块簇内不打重叠）
 */
public record ChunkOptions(int maxChars, int overlap) {

    public ChunkOptions {
        if (maxChars < 100) {
            maxChars = 100;
        }
        if (overlap < 0) {
            overlap = 0;
        }
        if (overlap >= maxChars / 2) {
            overlap = maxChars / 2; // 重叠不得超过块长一半（防死循环/块膨胀）
        }
    }

    public static ChunkOptions of(int maxChars, int overlap) {
        return new ChunkOptions(maxChars, overlap);
    }
}
