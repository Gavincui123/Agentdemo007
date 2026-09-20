package com.agentdemo007.capability.kb.parse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 文档解析器注册表（[[kb-ingest-design]]·任务2）：扩展名 → 解析器路由 + 兜底。
 *
 * <p>装配全部 {@link DocumentParser} bean（Spring 注入 List）；未注册扩展名兜底按平文处理
 * （二进制文件会解析出乱码/空——录入服务另有"可提取字符数=0 拒绝"护栏，不会污染向量库）。
 */
@Component
public class DocumentParserRegistry {

    private static final Logger log = LoggerFactory.getLogger(DocumentParserRegistry.class);

    private final Map<String, DocumentParser> byExtension = new HashMap<>();
    private final MarkdownTextParser fallback = new MarkdownTextParser();

    public DocumentParserRegistry(List<DocumentParser> parsers) {
        for (DocumentParser parser : parsers) {
            for (String ext : parser.extensions()) {
                DocumentParser prev = byExtension.put(ext.toLowerCase(Locale.ROOT), parser);
                if (prev != null) {
                    log.warn("扩展名 .{} 被多个解析器接管（{} 覆盖 {}）", ext,
                            parser.getClass().getSimpleName(), prev.getClass().getSimpleName());
                }
            }
        }
        log.info("知识库文档解析器就绪：支持扩展名 {}", sortedExtensions());
    }

    /** 支持的扩展名全集（管理台上传白名单口径）。 */
    public Set<String> supportedExtensions() {
        return Set.copyOf(byExtension.keySet());
    }

    public boolean supports(String fileName) {
        String ext = extensionOf(fileName);
        return ext != null && byExtension.containsKey(ext);
    }

    public String docTypeOf(String fileName) {
        String ext = extensionOf(fileName);
        return (ext != null && byExtension.containsKey(ext)) ? ext : "txt";
    }

    /** 解析（扩展名路由；未注册扩展名兜底平文——清洗+空护栏兜住脏输入）。 */
    public ParsedDocument parse(String fileName, InputStream in) throws Exception {
        String ext = extensionOf(fileName);
        DocumentParser parser = (ext != null) ? byExtension.getOrDefault(ext, fallback) : fallback;
        return parser.parse(in, fileName);
    }

    private static String extensionOf(String fileName) {
        if (fileName == null) {
            return null;
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return null;
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private List<String> sortedExtensions() {
        return byExtension.keySet().stream().sorted().toList();
    }
}
