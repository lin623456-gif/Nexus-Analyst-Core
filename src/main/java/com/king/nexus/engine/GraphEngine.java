package com.king.nexus.engine;

import com.king.nexus.utils.RedisUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 图执行引擎核心调度器 (GraphEngine - 事件驱动解耦架构)
 *
 * <p>核心职责：负责整个图工作流的上下文编排、节点(Node)路由调度、状态持久化管理以及全局 ReAct 容错兜底。
 * <p>核心特性：
 * 1. 响应式流式输出：基于 SSE (Server-Sent Events) 的机制提供非阻塞数据流编排与传输。
 * 2. 领域事件解耦：依托 Spring ApplicationEventPublisher 机制，实现全链路遥测日志与主业务链路的严格物理解耦。
 */
@Component
public class GraphEngine {

    // ==========================================
    // 1. 核心依赖组件 (Core Dependencies)
    // ==========================================

    // 节点注册表：维护图执行流中所有的计算节点，作为意图识别后动态路由分发的寻址基础。
    private final Map<String, Node> nodeMap;

    // 状态缓存客户端：处理上下文状态的生命周期，支撑会话连续性与潜在的向量检索机制。
    private final RedisUtils redisUtils;

    // 分布式事件发布器：用于下发异步领域事件，支撑微服务架构下的全链路追踪与解耦。
    private final ApplicationEventPublisher eventPublisher;

    // 业务异步执行器：依托 JDK 21 虚拟线程 (Virtual Threads) 实现，提供轻量级协程调度及高并发隔离能力。
    private final Executor businessExecutor = Executors.newVirtualThreadPerTaskExecutor();

    // ==========================================
    // 2. 引擎初始化装配 (Engine Initialization)
    // ==========================================
    /**
     * 引擎调度器初始化与依赖注入。
     *
     * @param nodes          系统中已注册的可用节点实例集合
     * @param redisUtils     分布式缓存与状态管理组件
     * @param eventPublisher 领域级上下文事件总线
     */
    @Autowired
    public GraphEngine(List<Node> nodes, RedisUtils redisUtils, ApplicationEventPublisher eventPublisher) {
        this.nodeMap = nodes.stream().collect(Collectors.toMap(Node::getId, Function.identity()));
        this.redisUtils = redisUtils;
        this.eventPublisher = eventPublisher;
        System.out.println(">>> 👑 帝国引擎初始化完成，已册封 " + nodeMap.size() + " 位节点将军，信号枪库已就位。");
    }

    // ==========================================
    // 3. 异步响应式流执行器 (Streaming Execution)
    // ==========================================
    /**
     * 基于流式响应 (SSE) 的核心工作流调度方法。
     *
     * <p>执行包含上下文编排、依赖装配与异步历史状态加载的预处理逻辑，并触发核心路由链路。
     *
     * @param query      用户触发工作流的原始输入请求
     * @param onMessage  流式结果片段分发通道 (SSE Emitter callback)
     * @param onComplete 调度链路正常终止的回调函数
     * @param onError    全局异常捕获及降级阻断的回调函数
     */
    public void runStream(String query,
                          java.util.function.Consumer<String> onMessage,
                          Runnable onComplete,
                          java.util.function.Consumer<Throwable> onError) {

        // 1. 实例化并构建当前会话链路的完整执行上下文 (Execution Context)
        NodeContext ctx = new NodeContext();
        ctx.setOriginalQuery(query);

        // --- 全链路追踪：生成并注入分布式 Trace ID ---
        String traceId = UUID.randomUUID().toString();
        ctx.setTraceId(traceId);

        // 用户身份标识与会话数据缓存键绑定 (目前采用固定硬编码，待重构)
        String userId = "user_001";
        String redisKey = "chat:history:" + userId;
        ctx.setUserId(userId);
        ctx.setRedisKey(redisKey);

        // 挂载下行流式响应通道句柄
        ctx.setSseEmitterCallback(onMessage);

        // --- 架构解耦支持：将应用事件发布总线注入下行上下文结构中 ---
        ctx.setEventPublisher(this.eventPublisher);

        // 2. 异步发起缓存读取操作，加载历史会话记录 (用于支撑多轮上下文编排、向量检索及 AST反序列化 状态还原)
        redisUtils.getListAsync(ctx.getRedisKey())
                .thenAcceptAsync(history -> {
                    try {
                        ctx.setHistoryList(history);
                        ctx.addLog(">>> 记忆管家汇报：读取到历史记录 " + history.size() + " 条");

                        // 触发基于有向无环图 (DAG) 或状态机的核心流转调度链路
                        executeNodes(ctx);

                        // 链路终点校验：提取计算完毕的最终推导结果并进行推送
                        if (ctx.getFinalAnswer() != null) {
                            onMessage.accept("[FINAL_ANSWER]" + ctx.getFinalAnswer());
                        }

                        // 释放流式连接通道，发起链路终止信号
                        onComplete.run();

                    } catch (Exception e) {
                        System.err.println("[全服警报] 流水线运行中发生致命异常: " + e.getMessage());
                        onError.accept(e);
                    }
                }, businessExecutor)
                .exceptionally(ex -> {
                    System.err.println("[全服警报] 引擎启动失败: " + ex.getMessage());
                    onError.accept(ex);
                    return null;
                });
    }

    // ==========================================
    // 4. 状态机流转调度引擎 (State Machine Execution)
    // ==========================================
    /**
     * 工作流节点间流转调度的核心控制循环。
     *
     * <p>基于拓扑结构执行节点的串联逻辑，内部已封装 ReAct容错 机制与防无限自旋的硬隔离边界控制。
     *
     * @param ctx 当前处理链路对应的完整上下文透传载体
     */
    private void executeNodes(NodeContext ctx) {
        ctx.addLog(">>> 车间流水线启动，正在处理指令: " + ctx.getOriginalQuery());

        String currentNodeId = "node_start";
        int step = 0;
        final int MAX_STEPS = 20;

        while (currentNodeId != null && step < MAX_STEPS) {

            Node node = nodeMap.get(currentNodeId);
            if (node == null) {
                ctx.addLog("❌ 找不到名为 [" + currentNodeId + "] 的将军！流水线强行中断。");
                break;
            }

            // --- 执行态追踪：持续迭代当前控制权所属节点的标识 ---
            // 保障日志采集、遥测审计系统能够精准溯源具体的触发算子与数据流向
            ctx.setCurrentNodeId(currentNodeId);

            ctx.addLog(">>> 档案袋已传给：" + currentNodeId);

            // 委派控制权：执行节点原生计算逻辑，并根据结果动态获取下一跳路由地址
            currentNodeId = node.execute(ctx);
            step++;
        }

        if (step >= MAX_STEPS) {
            ctx.addLog("⚠️ 警告：跑了太多圈，触发熔断防死锁机制！");
            ctx.setFinalAnswer("问题太复杂，我脑子绕晕了，任务强制终止。");
        }

        // 链路后置处理器：异步触发当前会话上下文数据的落盘与统一归档
        if (ctx.getFinalAnswer() != null) {
            redisUtils.archiveConversationAsync(
                    ctx.getRedisKey(),
                    "User: " + ctx.getOriginalQuery(),
                    "AI: " + ctx.getFinalAnswer()
            );
            ctx.addLog("✅ 引擎层：已派人去将本次对话刻入石碑 (Async)。");
        }

        ctx.addLog("✅ 流水线作业全部结束。");
    }

    // ==========================================
    // 5. 阻塞式执行降级接口 (Synchronous Fallback Execution)
    // ==========================================
    /**
     * 阻塞式工作流调度同步接口（主要提供向后兼容能力）。
     *
     * <p>复用底层异步执行器并封装为同步阻塞语义，同时保持领域事件解耦能力的完整可用性。
     *
     * @param query 用户原始输入
     * @return 包含最终推断结果且已装填完毕的 CompletableFuture 上下文实例
     */
    public CompletableFuture<NodeContext> run(String query) {
        NodeContext ctx = new NodeContext();
        ctx.setOriginalQuery(query);

        String traceId = UUID.randomUUID().toString();
        ctx.setTraceId(traceId);

        ctx.setUserId("user_001");
        ctx.setRedisKey("chat:history:user_001");
        ctx.setEventPublisher(this.eventPublisher); // 维持向下兼容性，保障同步阻塞调用链路依然支持领域事件分发

        return redisUtils.getListAsync(ctx.getRedisKey())
                .thenApplyAsync(history -> {
                    ctx.setHistoryList(history);
                    ctx.addLog(">>> 记忆管家汇报：读取到历史记录 " + history.size() + " 条");
                    executeNodes(ctx);
                    return ctx;
                }, businessExecutor)
                .exceptionally(ex -> {
                    ctx.addLog("❌ 致命错误: " + ex.getMessage());
                    ctx.setFinalAnswer("系统过载，请稍后再试。");
                    return ctx;
                });
    }
}
