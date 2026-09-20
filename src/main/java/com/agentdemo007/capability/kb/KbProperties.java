package com.agentdemo007.capability.kb;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.kb.*} 配置绑定（知识库录入·[[kb-ingest-design]]）。
 *
 * <pre>
 * app:
 *   kb:
 *     chunk:
 *       max-chars: 500   # 通用切块上限（字符）；专业领域条款切块自动识别、条款原子不硬切
 *       overlap: 80      # 超长段落递归切分的重叠窗口
 *     preview-limit: 50  # 试运行/录入结果返回的切块预览条数上限
 * </pre>
 */
@ConfigurationProperties(prefix = "app.kb")
public class KbProperties {

    private final Chunk chunk = new Chunk();
    private int previewLimit = 50;

    public Chunk getChunk() {
        return chunk;
    }

    public int getPreviewLimit() {
        return previewLimit;
    }

    public void setPreviewLimit(int previewLimit) {
        this.previewLimit = previewLimit;
    }

    /** 切块参数（通用递归切块口径；条款切块继承 max-chars 作"条款簇"打包上限）。 */
    public static class Chunk {

        private int maxChars = 500;
        private int overlap = 80;

        public int getMaxChars() {
            return maxChars;
        }

        public void setMaxChars(int maxChars) {
            this.maxChars = maxChars;
        }

        public int getOverlap() {
            return overlap;
        }

        public void setOverlap(int overlap) {
            this.overlap = overlap;
        }
    }
}
