package com.agentdemo007.eval;

import java.util.List;

/**
 * 评测黄金数据集（Phase 15·T70，③环节测评——标准 stage 集合）。
 *
 * <p>承载 dev-plan §Phase15 指定的标准 8 stage classpath 资源（injection/intent/routing/rag/
 * tool/hitl/degradation/audit）。用 wrapper record 而非裸 {@code List<String>}——避 Spring 对
 * {@code List<String>} 注入按"所有 String bean"解析的歧义，且强类型收口（第四原则，非 Map/裸集合）。
 *
 * @param resources classpath 资源路径列表（如 {@code eval/injection.json}）
 */
public record EvalSuite(List<String> resources) {
}
