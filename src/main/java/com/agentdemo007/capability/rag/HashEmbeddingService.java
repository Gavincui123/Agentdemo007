package com.agentdemo007.capability.rag;

import java.util.ArrayList;
import java.util.List;

/**
 * 哈希嵌入服务（第四层·dev 向量化内核，确定性 token-bag）。
 *
 * <p>token 化（ASCII 词连续累积，CJK 单字成 token，标点/空白分隔）→ 每个桶
 * （{@code floorMod(hash, DIMENSION)}）计数 → L2 归一化。相同文本向量一致；共享 token 文本余弦相似度高。
 * 无随机性、无外部模型，单测可确定断言。prod 由 LangChain4j Embedding 桥接覆盖。
 */
public class HashEmbeddingService implements EmbeddingService {

    /** 嵌入维度。 */
    static final int DIMENSION = 64;

    @Override
    public float[] embed(String text) {
        float[] v = new float[DIMENSION];
        if (text == null || text.isBlank()) {
            return v;
        }
        for (String token : tokenize(text)) {
            int bucket = Math.floorMod(token.hashCode(), DIMENSION);
            v[bucket] += 1.0f;
        }
        return normalize(v);
    }

    private static float[] normalize(float[] v) {
        double norm = 0;
        for (float f : v) {
            norm += f * f;
        }
        norm = Math.sqrt(norm);
        if (norm == 0) {
            return v;
        }
        for (int i = 0; i < v.length; i++) {
            v[i] = (float) (v[i] / norm);
        }
        return v;
    }

    /** 包级可见：dev 链路（{@link Reranker} BM25）复用同一分词口径，保证 token 语义一致。 */
    static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                flush(buf, tokens);
            } else if (isCjk(c)) {
                flush(buf, tokens);
                tokens.add(String.valueOf(c));
            } else if (Character.isLetterOrDigit(c)) {
                buf.append(c);
            } else {
                flush(buf, tokens); // 标点分隔
            }
        }
        flush(buf, tokens);
        return tokens;
    }

    private static void flush(StringBuilder buf, List<String> out) {
        if (!buf.isEmpty()) {
            out.add(buf.toString());
            buf.setLength(0);
        }
    }

    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF) // CJK 统一汉字
                || (c >= 0x3400 && c <= 0x4DBF); // CJK 扩展 A
    }
}
