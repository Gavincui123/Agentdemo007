package com.agentdemo007.capability.rag;

/**
 * 向量化服务（第四层·RAG 链路入口）。
 *
 * <p>把文本映射为定长稠度向量，供 {@link VectorStore} 索引与检索。引擎无关 seam：
 * dev 用 {@link HashEmbeddingService}（确定性 token-bag，无真实模型），prod 覆盖为
 * LangChain4j Embedding 桥接（随 LangChain4j 接入，延后——避免过早引入 langchain4j-core 重依赖）。
 */
public interface EmbeddingService {

    /** @return 文本的定长向量（L2 归一化）；空文本返回零向量 */
    float[] embed(String text);
}
