package com.agentdemo007.capability.plan;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 能力路由规划（数据载体）。
 *
 * <p>RoutePlan 描述一条查询该走哪些能力（RAG / Tool）、并行还是串行。由 RoutePlanner 产出，
 * CapabilityStage 消费。空 plan（chit-chat / 无能力需求）= 跳过所有能力直接出答。
 *
 * <p><b>三层权责（用户 2026-09-10 钦定）</b>：路由 = 模型出候选 → 服务端白名单收敛 → 规则护栏兜底。
 * 本类只是<b>结果载体</b>，不含决策逻辑——谁产、经谁收敛、被谁覆盖，由 RoutePlanner 决定，
 * 审计经 RoutePlanStep 收口（不污染 plan 本身）。
 *
 * <ul>
 *   <li>{@link Capability#RAG} 检索增强；{@link Capability#TOOL} 工具调用。</li>
 *   <li>{@link Mode#PARALLEL} 并行（默认，降延迟）；{@link Mode#SERIAL} 串行（有声明依赖时）；
 *       {@link Mode#NONE} 无能力（空 plan）。</li>
 *   <li>{@code serialOrder}：仅 SERIAL 下携带执行顺序；PARALLEL/NONE 下为空。</li>
 * </ul>
 *
 * <p>不变式：能力集为空时，mode 归一为 NONE（防御——无论传入什么 mode，空能力即无能力）。
 */
public record RoutePlan(Set<Capability> capabilities, Mode mode, List<Capability> serialOrder) {

    /** 可调度的能力。 */
    public enum Capability { RAG, TOOL }

    /** 能力执行模式。 */
    public enum Mode { PARALLEL, SERIAL, NONE }

    public RoutePlan {
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
        serialOrder = serialOrder == null ? List.of() : List.copyOf(serialOrder);
        mode = capabilities.isEmpty() ? Mode.NONE : mode;
    }

    /** 该 plan 是否需要某能力。 */
    public boolean needs(Capability c) {
        return capabilities.contains(c);
    }

    /** 空能力 plan（chit-chat / 纯出答）。 */
    public boolean isEmpty() {
        return capabilities.isEmpty();
    }

    /** 无能力（chit-chat 等跳过所有能力）。 */
    public static RoutePlan none() {
        return new RoutePlan(Set.of(), Mode.NONE, List.of());
    }

    /** 单能力（默认并行）。 */
    public static RoutePlan of(Capability single) {
        return new RoutePlan(Set.of(single), Mode.PARALLEL, List.of());
    }

    /** 双能力并行。 */
    public static RoutePlan bothParallel() {
        return new RoutePlan(EnumSet.allOf(Capability.class), Mode.PARALLEL, List.of());
    }

    /** 双能力串行，按给定顺序执行。 */
    public static RoutePlan bothSerial(Capability first, Capability second) {
        return new RoutePlan(EnumSet.allOf(Capability.class), Mode.SERIAL, List.of(first, second));
    }
}
