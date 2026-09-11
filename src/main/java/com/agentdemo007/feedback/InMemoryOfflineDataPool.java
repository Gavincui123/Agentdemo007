package com.agentdemo007.feedback;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 离线数据池·内存实现（Phase 15·dev 占位，prod 用 JPA/对象存储覆盖）。
 *
 * <p>线程安全（{@link ConcurrentLinkedDeque}）；{@code drain} 原子取出并清空。
 * 微调闭环在 dev 无真实存储时由本类兜底，保证反馈收集链路可跑通。
 */
@Component
public class InMemoryOfflineDataPool implements OfflineDataPool {

    private final ConcurrentLinkedDeque<TrainingSample> pool = new ConcurrentLinkedDeque<>();

    @Override
    public void store(TrainingSample sample) {
        if (sample == null) {
            return;
        }
        pool.add(sample);
    }

    @Override
    public List<TrainingSample> drain() {
        List<TrainingSample> snapshot = new ArrayList<>();
        TrainingSample s;
        while ((s = pool.poll()) != null) {
            snapshot.add(s);
        }
        return snapshot;
    }

    @Override
    public int size() {
        return pool.size();
    }
}
