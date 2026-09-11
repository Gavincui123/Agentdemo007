package com.agentdemo007.output;

/**
 * 结构化输出网关产出（第七层·校验/兜底后的最终输出）。
 *
 * @param text     输出文本（校验通过的原文，或兜底话术）
 * @param degraded 是否经历了 Schema 校验耗尽兜底（§5.12 结构化输出行）
 */
public record OutputResult(String text, boolean degraded) {
}
