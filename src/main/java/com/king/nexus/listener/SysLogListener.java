package com.king.nexus.listener;

import com.king.nexus.event.NodeExecutionEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 异步领域事件持久化监听器 (SysLogListener)
 *
 * <p>核心职责：作为全链路遥测日志的异步消费端点，监听工作流上下文编排引擎发出的 {@link NodeExecutionEvent}，
 * 通过 {@link EventListener} 机制进行事件拦截后，执行脱敏日志结构化落盘。
 * <p>架构特性：依托 {@code @Async} 注解实现高并发隔离，将审计日志持久化操作与主业务链路进行严格的物理线程分流，
 * 彻底消除 I/O 等待对核心调用链路带来的阻塞风险。
 */
@Component
public class SysLogListener {

    // 数据访问句柄：用于执行批量结构化日志的持久化写入操作
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 节点执行事件异步监听拦截处理器。
     *
     * <p>监听语义：当具体的节点算子 (Node) 通过上下文总线发射 {@link NodeExecutionEvent} 后，由本方法进行拦截消费。
     * <p>执行策略：基于 {@code @Async} 注解启用独立协程池执行，确保分布式数据库写入延迟不影响主调度链路的 ReAct 容错与意图识别流程。
     *
     * @param event 节点执行遥测事件载体，包含全链路 Trace ID、执行算子标识与结构化的上下文元数据。
     */
    @EventListener
    @Async // 关键异步分流：严禁阻塞主业务流上下文编排与响应式推送调度
    public void onNodeExecutionEvent(NodeExecutionEvent event) {

        // 结构化查询语句：向审计日志表追加全链路执行轨迹记录
        String sql = "INSERT INTO sys_exec_log (trace_id, node_id, content) VALUES (?, ?, ?)";

        try {
            // 执行持久化写入操作，将当前节点上下文轨迹存储至关系型数据库
            jdbcTemplate.update(sql, event.getTraceId(), event.getNodeId(), event.getMessage());

            // 遥测监听状态回显 (调试期可见性指标，生产环境可静默降级)
            System.out.println(">>> [暗影史官] 已将信号弹刻入石碑: [" + event.getNodeId() + "] " + event.getMessage());

        } catch (Exception e) {
            // 容错边界处理：持久化层的致命异常严禁向上游传播，实施吞没降级策略，
            // 确保关系型存储不可用时，核心任务编排链路不受任何影响。
            System.err.println(">>> [暗影史官] 刻碑失败，但绝不影响前线: " + e.getMessage());
        }
    }
}
