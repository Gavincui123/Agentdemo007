package com.agentdemo007.langgraph;

import org.bsc.langgraph4j.serializer.StateSerializer;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.AgentStateFactory;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;

/**
 * 无克隆状态序列化器（Phase 14·避开 Java 序列化）。
 *
 * <p>langgraph4j 默认 {@code ObjectStreamStateSerializer} 用 Java 序列化克隆状态——但我们的共享状态
 * 携带 {@code PipelineContext}（强类型收口对象，刻意非 {@code Serializable}：不为框架需求膨胀
 * 领域对象，§5.14），Java 序列化会抛 {@code NotSerializableException}。
 *
 * <p>本序列化器覆写 {@link #write}/{@link #read} 为"stash 引用 + 原样返回"——使 langgraph4j
 * {@code cloneObject} 返回同一状态引用（无克隆），节点间共享同一 {@code PipelineContext}，
 * 副作用自然传递（与线性 {@code PipelineOrchestrator} 共享同一 {@code context} 语义一致）。
 *
 * <p>代价：放弃 langgraph4j 自带状态快照/回滚（我们无此需求——审计/持久化自有收口，§5.11/§5.12），
 * 换取不污染领域对象 + 共享引用语义。**非线程安全**：{@code stashed} 为实例字段，
 * 须 per-request 新建（{@link AgentStateGraph#build()} 每次构造本类，{@code GraphExecutor} 每请求
 * 新建图实例）。
 */
public class NoCloneStateSerializer extends StateSerializer<AgentState> {

    /** 最近一次 write 暂存的状态引用（read 原样返回，实现无克隆）。 */
    private AgentState stashed;

    public NoCloneStateSerializer(AgentStateFactory<AgentState> factory) {
        super(factory);
    }

    @Override
    public void write(AgentState state, ObjectOutput out) throws IOException {
        this.stashed = state; // 暂存引用，不序列化值（避开 PipelineContext 非序列化）
        out.writeInt(0); // 占位标记（保证流非空，read 对称消费）
    }

    @Override
    public AgentState read(ObjectInput in) throws IOException, ClassNotFoundException {
        in.readInt(); // 消费占位标记
        return stashed; // 原样返回（无克隆，共享引用）
    }
}
