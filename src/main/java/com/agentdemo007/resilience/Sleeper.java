package com.agentdemo007.resilience;

/**
 * 睡眠抽象（测试缝）：重试退避的可注入睡眠接口。
 *
 * <p>测试用记录型实现（不真实睡眠、只记延迟）；生产实现走 {@link Thread#sleep}，
 * 被中断时恢复中断标志（不抛检查异常，避免污染重试模板签名）。
 */
@FunctionalInterface
public interface Sleeper {

    void sleepMs(long millis);
}
