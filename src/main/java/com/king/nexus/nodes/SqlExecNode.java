package com.king.nexus.nodes;

import com.king.nexus.engine.Node;
import com.king.nexus.engine.NodeContext;
import com.pgvector.PGvector;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * SQL 执行与语义缓存节点 (SqlExecNode - 异步向量缓存增强版)
 *
 * <p>核心职责：接收上游结构化生成的 SQL 语句，执行物理查询并将结果集挂载至上下文。
 * 同时，通过异步非阻塞方式将成功执行的查询文本及其向量表征持久化至语义缓存（semantic_cache），
 * 为后续相似意图的向量检索与快速路由提供数据支撑。
 * <p>容错机制：支持 ReAct 风格的重试容错，当执行异常时会将错误信息反馈至 SQL 生成节点，
 * 实现基于反馈的自动修复闭环；内置最大重试次数熔断，防止无限自旋导致的资源耗尽。
 */
@Component
public class SqlExecNode implements Node {

    // JDBC 操作句柄：同时承载查询执行与异步缓存写入的双重职责，对接物理数据源。
    @Autowired
    private JdbcTemplate jdbcTemplate;

    // 文本嵌入模型：将原始查询语句转换为高维向量，作为语义缓存的检索键。
    @Autowired
    private EmbeddingModel embeddingModel;

    // 异步缓存写入执行器：基于虚拟线程的隔离线程池，确保向量持久化操作不与主查询争抢关键链路资源，实现高并发隔离。
    private final Executor asyncExecutor = Executors.newVirtualThreadPerTaskExecutor();

    @Override
    public String getId() {
        return "node_sql_exec";
    }

    /**
     * 执行 SQL 查询并进行上下文状态维护。
     *
     * <p>处理流程：
     * <ol>
     *   <li>物理查询执行：通过 {@link JdbcTemplate} 执行上下文中的 SQL 语句，获取结构化结果集。</li>
     *   <li>异步语义缓存写入：在查询成功后，利用虚拟线程异步计算原始查询的文本嵌入，并与 SQL 一并写入
     *       semantic_cache 表，用于支撑后续的向量检索与意图识别加速。</li>
     *   <li>异常检测与重试回退：当执行失败时，将异常信息注入上下文，并通过路由至 SQL 生成节点触发 ReAct 修复；
     *       若重试次数超过阈值（3次），则直接挂载兜底应答并终止链路。</li>
     * </ol>
     *
     * @param ctx 当前执行上下文，包含待执行 SQL、原始查询、错误栈及重试计数器等元数据
     * @return 下游节点标识：成功时返回 "node_reporter"；失败且未熔断时返回 "node_sql_gen"；熔断时返回 null
     */
    @Override
    public String execute(NodeContext ctx) {
        String sql = ctx.getGeneratedSql();
        ctx.addLog("SqlExecNode : 行刑官就位，高维子弹已上膛...");
        ctx.addLog(">>> 准备执行的指令: [" + sql + "]");

        try {
            // ==========================================
            // 阶段一：物理查询执行
            // 将生成的 SQL 语句提交至数据库执行，并捕获结构化查询结果
            // ==========================================
            List<Map<String, Object>> result = jdbcTemplate.queryForList(sql);

            ctx.addLog("✅ 砰！打中了！(SQL执行成功)，共缴获 " + result.size() + " 条战利品(数据)。");
            ctx.setQueryResult(result);
            ctx.setLastError(null); // 重置错误栈，表示当前查询已正常完成

            // ==========================================
            // 阶段二：异步语义缓存写入
            // 将查询成功的 SQL 语句与原始查询向量化后的表示写入语义缓存，
            // 整个过程采用异步非阻塞方式执行，避免 I/O 延迟阻塞主查询链路
            // ==========================================
            String originalQuery = ctx.getOriginalQuery();

            CompletableFuture.runAsync(() -> {
                try {
                    // 1. 将原始查询文本转换为高维向量，向量的维度由底层嵌入模型决定
                    float[] queryVector = embeddingModel.embed(originalQuery).content().vector();
                    PGvector pgQueryVector = new PGvector(queryVector);

                    // 2. 通过 JDBC 模板执行 INSERT，将原始查询、查询向量及生成的 SQL 持久化至语义缓存表
                    String insertSql = "INSERT INTO semantic_cache (original_query, query_embedding, generated_sql) VALUES (?, ?, ?)";
                    jdbcTemplate.update(insertSql, originalQuery, pgQueryVector, sql);

                    System.out.println(">>> [跑腿小弟] ✅ 成功将这把好枪存入高维错题本 (semantic_cache)！");
                } catch (Exception e) {
                    System.err.println(">>> [跑腿小弟] ❌ 存入高维错题本失败：" + e.getMessage());
                }
            }, asyncExecutor);

            // 查询成功，将上下文路由至数据汇报节点，完成自然语言摘要的生成
            return "node_reporter";

        } catch (Exception e) {
            // ==========================================
            // 阶段三：异常处理与自愈重试 (ReAct 容错机制)
            // ==========================================
            String errorMsg = (e.getCause() != null) ? e.getCause().getMessage() : e.getMessage();
            ctx.addLog("❌ 炸膛了！(SQL执行失败)，数据库老师骂道: " + errorMsg);

            ctx.setLastError(errorMsg);
            ctx.setRetryCount(ctx.getRetryCount() + 1);

            // 熔断保护：当重试次数超过上限时强制终止流程，防止无限重试导致资源耗尽
            if (ctx.getRetryCount() > 3) {
                ctx.setFinalAnswer("对不起老板，翻译官重写了3次SQL都报错了。最后的死因：" + errorMsg);
                ctx.addLog("⚠️ 警告：重试次数超限，任务强制终止！");
                return null;
            }

            // 触发 ReAct 重试：将当前上下文回退至 SQL 生成节点，基于异常描述进行修正重试
            ctx.addLog(">>> 行刑官决定再给翻译官一次机会，把错题扔回 SqlGenNode 重试...");
            return "node_sql_gen";
        }
    }
}
