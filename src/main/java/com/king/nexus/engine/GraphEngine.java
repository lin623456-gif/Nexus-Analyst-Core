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
 * 👑 帝国总司令部 (GraphEngine - 事件驱动解耦版)
 *
 * 作用：统筹一切。接收任务，指挥将军们(Node)干活，管理记忆，兜底错误。
 * 绝招：
 * 1. 流式直播 (SSE)。
 * 2. 给公文包配发信号枪 (EventPublisher)，实现写日志与主业务的彻底物理解耦。
 */
@Component
public class GraphEngine {

    // ==========================================
    // 1. 司令部的核心资产 (装备库)
    // ==========================================

    // 【花名册】：所有的节点 (Node) 将军都在这里排队听令。
    private final Map<String, Node> nodeMap;

    // 【记忆大管家】：负责去地下室(Redis)和桌面(Caffeine)搬运档案。
    private final RedisUtils redisUtils;

    // 【帝国的信号枪库】：用来给公文包配发信号枪，触发异步事件。
    private final ApplicationEventPublisher eventPublisher;

    // 【幽灵劳工营】：JDK 21 的黑科技虚拟线程池。
    private final Executor businessExecutor = Executors.newVirtualThreadPerTaskExecutor();

    // ==========================================
    // 2. 司令部开张大典 (构造函数)
    // ==========================================
    @Autowired
    public GraphEngine(List<Node> nodes, RedisUtils redisUtils, ApplicationEventPublisher eventPublisher) {
        this.nodeMap = nodes.stream().collect(Collectors.toMap(Node::getId, Function.identity()));
        this.redisUtils = redisUtils;
        this.eventPublisher = eventPublisher;
        System.out.println(">>> 👑 帝国引擎初始化完成，已册封 " + nodeMap.size() + " 位节点将军，信号枪库已就位。");
    }

    // ==========================================
    // 3. 流式战役指挥 (Streaming Run)
    // ==========================================
    public void runStream(String query,
                          java.util.function.Consumer<String> onMessage,
                          Runnable onComplete,
                          java.util.function.Consumer<Throwable> onError) {

        // 1. 准备空白档案袋 (Context)
        NodeContext ctx = new NodeContext();
        ctx.setOriginalQuery(query);

        // --- 核心解耦升级：给公文包打上防伪追踪码 ---
        String traceId = UUID.randomUUID().toString();
        ctx.setTraceId(traceId);

        // 身份锚定 (目前写死)
        String userId = "user_001";
        String redisKey = "chat:history:" + userId;
        ctx.setUserId(userId);
        ctx.setRedisKey(redisKey);

        // 装上前端对讲机 (SSE)
        ctx.setSseEmitterCallback(onMessage);

        // --- 核心解耦升级：把信号枪塞进公文包 ---
        ctx.setEventPublisher(this.eventPublisher);

        // 2. 异步呼叫管家拿记忆
        redisUtils.getListAsync(ctx.getRedisKey())
                .thenAcceptAsync(history -> {
                    try {
                        ctx.setHistoryList(history);
                        ctx.addLog(">>> 记忆管家汇报：读取到历史记录 " + history.size() + " 条");

                        // 跑核心流水线
                        executeNodes(ctx);

                        // 活全干完了！发送最终答案。
                        if (ctx.getFinalAnswer() != null) {
                            onMessage.accept("[FINAL_ANSWER]" + ctx.getFinalAnswer());
                        }

                        // 挂电话！
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
    // 4. 车间流水线 (状态机流转逻辑)
    // ==========================================
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

            // --- 核心解耦升级：把当前接盘的将军名字写在公文包上 ---
            // 这样史官记录日志时，才知道这句话是谁说的。
            ctx.setCurrentNodeId(currentNodeId);

            ctx.addLog(">>> 档案袋已传给：" + currentNodeId);

            // 将军干活！
            currentNodeId = node.execute(ctx);
            step++;
        }

        if (step >= MAX_STEPS) {
            ctx.addLog("⚠️ 警告：跑了太多圈，触发熔断防死锁机制！");
            ctx.setFinalAnswer("问题太复杂，我脑子绕晕了，任务强制终止。");
        }

        // 收尾工作：统一归档记忆
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
    // 5. 老版本兼容 (同步阻塞版) - 同步增加 EventPublisher 支持
    // ==========================================
    public CompletableFuture<NodeContext> run(String query) {
        NodeContext ctx = new NodeContext();
        ctx.setOriginalQuery(query);

        String traceId = UUID.randomUUID().toString();
        ctx.setTraceId(traceId);

        ctx.setUserId("user_001");
        ctx.setRedisKey("chat:history:user_001");
        ctx.setEventPublisher(this.eventPublisher); // 保证老接口也能触发日志事件

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
