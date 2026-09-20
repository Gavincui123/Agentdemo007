package com.agentdemo007.capability.kb;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 来源标识编解码单测（[[kb-ingest-design]]·任务3）：docUid/source/prefix 往返、
 * 非托管来源判空、docNo 消毒防前缀歧义。
 */
class KbSourceRefTest {

    @Test
    void encodeAndDecodeRoundTrip() {
        String docUid = KbSourceRef.docUid(KbNamespace.PUBLIC, "refund-faq");
        assertThat(docUid).isEqualTo("kb:PUBLIC:refund-faq");

        String source = KbSourceRef.source(docUid, 2, 3);
        assertThat(source).isEqualTo("kb:PUBLIC:refund-faq:v2#3");
        assertThat(KbSourceRef.docUidOf(source)).isEqualTo(docUid);
        assertThat(KbSourceRef.versionPrefix(docUid)).isEqualTo("kb:PUBLIC:refund-faq:v");
    }

    @Test
    void nonManagedSourcesAreNotManaged() {
        assertThat(KbSourceRef.docUidOf("kb-refund")).isNull();       // dev 种子
        assertThat(KbSourceRef.docUidOf("corpus/faq/01.md")).isNull(); // Python 流水线相对路径
        assertThat(KbSourceRef.docUidOf(null)).isNull();
    }

    @Test
    void prefixDeletionHasNoAmbiguityBetweenSimilarDocNos() {
        String doc1 = KbSourceRef.docUid(KbNamespace.PUBLIC, "doc1");
        String doc10 = KbSourceRef.docUid(KbNamespace.PUBLIC, "doc10");
        String doc10Source = KbSourceRef.source(doc10, 1, 1);
        // doc1 的删除前缀不得误伤 doc10
        assertThat(doc10Source.startsWith(KbSourceRef.versionPrefix(doc1))).isFalse();
        assertThat(doc10Source.startsWith(KbSourceRef.versionPrefix(doc10))).isTrue();
    }

    @Test
    void docNoSanitizedAgainstSeparatorInjection() {
        assertThat(KbSourceRef.sanitizeDocNo(" 退货 FAQ ")).isEqualTo("退货-FAQ");
        assertThatThrownBy(() -> KbSourceRef.sanitizeDocNo(" : ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KbSourceRef.sanitizeDocNo(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
