package com.agentdemo007.capability.kb;

/**
 * 知识库来源标识编解码（[[kb-ingest-design]]·任务3 元数据）。
 *
 * <p>VectorStore 的 {@code source} 字段是检索片段与文档元数据之间的唯一桥梁（RagFragment 不冗余
 * namespace/version 字段，Chroma metadata 契约不动）。格式：
 * <pre>
 *   docUid = kb:{namespace}:{docNo}                      // 文档级（跨版本不变）
 *   source = {docUid}:v{version}#{seq}                   // 片段级（例 kb:PUBLIC:refund-faq:v2#3）
 * </pre>
 * 版本更替/删除按 docUid 前缀整批删除向量（docNo 已消毒不含 ':'，前缀无歧义）。
 *
 * <p><b>向后兼容</b>：不以 {@code kb:} 开头的 source（dev 种子 kb-refund / Python 流水线相对路径）
 * 视为"库外历史片段"——权限过滤恒放行（公开语义），不参与版本管理。
 */
public final class KbSourceRef {

    /** 托管来源前缀（区分录入通道片段与种子/Python 流水线片段）。 */
    public static final String PREFIX = "kb:";

    private KbSourceRef() {
    }

    /** 文档级标识：kb:{namespace}:{docNo}。 */
    public static String docUid(KbNamespace namespace, String docNo) {
        return PREFIX + namespace.name() + ":" + sanitizeDocNo(docNo);
    }

    /** 片段级来源：{docUid}:v{version}#{seq}。 */
    public static String source(String docUid, int version, int seq) {
        return docUid + ":v" + version + "#" + seq;
    }

    /** 片段删除前缀：{docUid}:v（一个文档同一版本的全部片段）。 */
    public static String versionPrefix(String docUid) {
        return docUid + ":v";
    }

    /**
     * 从片段 source 提取 docUid；非托管来源（无 {@link #PREFIX}）返回 null。
     */
    public static String docUidOf(String source) {
        if (source == null || !source.startsWith(PREFIX)) {
            return null;
        }
        int vIdx = source.lastIndexOf(":v");
        int sIdx = source.lastIndexOf("#");
        if (vIdx < 0 || sIdx < vIdx) {
            return source; // 无版本后缀（异常形态），按整体当 docUid（过滤按状态兜底）
        }
        return source.substring(0, vIdx);
    }

    /** docNo 消毒：仅允许字母/数字/中日韩文字/._-，空白与分隔符替换为 '-'（防 source 前缀歧义）。 */
    public static String sanitizeDocNo(String docNo) {
        if (docNo == null || docNo.isBlank()) {
            throw new IllegalArgumentException("docNo 不能为空");
        }
        String cleaned = docNo.trim().replaceAll("[\\s:#|]+", "-");
        if (cleaned.isBlank() || "-".equals(cleaned)) {
            throw new IllegalArgumentException("docNo 消毒后为空：" + docNo);
        }
        return cleaned;
    }
}
