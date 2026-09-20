package com.agentdemo007.capability.rag.chroma;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code vectorstore.chroma.*} 配置绑定（真实 RAG·Chroma 向量库接入）。
 *
 * <p>Chroma 服务端由 Python 入库流水线侧部署（chromadb/chroma:1.0.8，v2 API，见
 * {@code scripts/rag-ingest/README.md}）；Java 侧只读检索 + 幂等 upsert，不建集合——
 * 集合/维度/cosine 度量由入库流水线定（换嵌入模型 = 新向量空间 = 全量重灌）。
 *
 * <pre>
 * vectorstore:
 *   type: chroma          # inmemory（默认 dev）| chroma
 *   chroma:
 *     base-url: ${CHROMA_BASE_URL:http://localhost:8001}
 *     collection: ${CHROMA_COLLECTION:kb_customer_service}
 *     api-key: ${CHROMA_TOKEN:}   # 自建无鉴权留空；有鉴权时 Bearer 注入
 * </pre>
 */
@ConfigurationProperties(prefix = "vectorstore.chroma")
public class ChromaProperties {

    /** Chroma 服务端地址（同机部署 localhost:8001；远程填 http://host:port）。 */
    private String baseUrl = "http://localhost:8001";

    /** 集合名（与入库流水线一致，kb_customer_service）。 */
    private String collection = "kb_customer_service";

    /** 可选 Bearer token（服务端开启鉴权时注入；密钥走环境变量，不落明文）。 */
    private String apiKey = "";

    /** Chroma v2 租户（默认 default_tenant）。 */
    private String tenant = "default_tenant";

    /** Chroma v2 数据库（默认 default_database）。 */
    private String database = "default_database";

    /** HTTP 连接超时（毫秒）。 */
    private int connectTimeoutMs = 5_000;

    /** HTTP 读超时（毫秒）。 */
    private int readTimeoutMs = 15_000;

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getCollection() { return collection; }
    public void setCollection(String collection) { this.collection = collection; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getTenant() { return tenant; }
    public void setTenant(String tenant) { this.tenant = tenant; }
    public String getDatabase() { return database; }
    public void setDatabase(String database) { this.database = database; }
    public int getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
    public int getReadTimeoutMs() { return readTimeoutMs; }
    public void setReadTimeoutMs(int readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }
}
