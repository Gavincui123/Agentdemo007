package com.agentdemo007.capability.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link FailoverEmbeddingService} 单测（稠密主备容灾）。
 *
 * <p>断言：主成功即返回（备不调）；主失败→切备；主备全失败→抛（HybridRetriever 降级前提）；
 * 空文本→零向量、不调 provider（契约同单 provider）。
 */
class FailoverEmbeddingServiceTest {

    @Test
    void primarySucceeds_backupNotCalled() {
        EmbeddingService primary = text -> new float[]{0.1f};
        EmbeddingService backup = text -> { throw new AssertionError("backup 不应被调"); };
        FailoverEmbeddingService svc = new FailoverEmbeddingService(List.of(primary, backup));

        assertThat(svc.embed("hi")).containsExactly(0.1f);
    }

    @Test
    void primaryFails_backupSucceeds() {
        EmbeddingService primary = text -> { throw new RuntimeException("主挂"); };
        EmbeddingService backup = text -> new float[]{0.2f};
        FailoverEmbeddingService svc = new FailoverEmbeddingService(List.of(primary, backup));

        assertThat(svc.embed("hi")).containsExactly(0.2f);
    }

    @Test
    void allFail_throwsLastException() {
        EmbeddingService p1 = text -> { throw new RuntimeException("e1"); };
        EmbeddingService p2 = text -> { throw new RuntimeException("e2"); };
        FailoverEmbeddingService svc = new FailoverEmbeddingService(List.of(p1, p2));

        assertThatThrownBy(() -> svc.embed("hi")).isInstanceOf(RuntimeException.class);
    }

    @Test
    void blankText_returnsEmpty_noProviderCalled() {
        EmbeddingService p = text -> { throw new AssertionError("空文本不应调 provider"); };
        FailoverEmbeddingService svc = new FailoverEmbeddingService(List.of(p));

        assertThat(svc.embed("")).isEmpty();
        assertThat(svc.embed(null)).isEmpty();
    }

    @Test
    void noProviders_nonBlank_throws() {
        FailoverEmbeddingService svc = new FailoverEmbeddingService(List.of());
        assertThatThrownBy(() -> svc.embed("hi")).isInstanceOf(RuntimeException.class);
    }
}
