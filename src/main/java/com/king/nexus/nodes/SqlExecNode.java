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
 * 👑 帝国首席行刑官 (高维武装升级版)
 * 作用：拿着翻译官写好的 SQL 去数据库“开枪”。
 * 绝招：打中后，把这把好枪(SQL)和它的瞄准镜(老板问题的向量)，异步存进高维错题本(semantic_cache)。
 */
@Component
public class SqlExecNode implements Node {

    @Autowired
    private JdbcTemplate jdbcTemplate; // 行刑官的枪 (执行 SQL 和写缓存都用它)

    @Autowired
    private EmbeddingModel embeddingModel; // 用来把老板的问题变成高维子弹，存进错题本

    // 幽灵劳工营 (专门用来后台写缓存，绝不卡主线任务)
    private final Executor asyncExecutor = Executors.newVirtualThreadPerTaskExecutor();

    @Override
    public String getId() {
        return "node_sql_exec";
    }

    @Override
    public String execute(NodeContext ctx) {
        String sql = ctx.getGeneratedSql();
        ctx.addLog("SqlExecNode : 行刑官就位，高维子弹已上膛...");
        ctx.addLog(">>> 准备执行的指令: [" + sql + "]");

        try {
            // ==========================================
            // 第一招：【开枪】(执行业务查询)
            // ==========================================
            List<Map<String, Object>> result = jdbcTemplate.queryForList(sql);

            ctx.addLog("✅ 砰！打中了！(SQL执行成功)，共缴获 " + result.size() + " 条战利品(数据)。");
            ctx.setQueryResult(result);
            ctx.setLastError(null); // 清空案底，原谅翻译官

            // ==========================================
            // 第二招：【高维入库】(异步存入 semantic_cache)
            // 婴儿级解释：既然这把枪好用，我们就把它和老板的问题(转成向量)，一起存进数据库。
            // 为什么用异步？因为算向量和写数据库很慢，不能让老板等。我们派跑腿小弟去干。
            // ==========================================
            String originalQuery = ctx.getOriginalQuery();

            CompletableFuture.runAsync(() -> {
                try {
                    // 1. 把老板的问题变成 512 维的数学矩阵
                    float[] queryVector = embeddingModel.embed(originalQuery).content().vector();
                    PGvector pgQueryVector = new PGvector(queryVector);

                    // 2. 存进高维错题本 (注意字段名)
                    String insertSql = "INSERT INTO semantic_cache (original_query, query_embedding, generated_sql) VALUES (?, ?, ?)";
                    jdbcTemplate.update(insertSql, originalQuery, pgQueryVector, sql);

                    System.out.println(">>> [跑腿小弟] ✅ 成功将这把好枪存入高维错题本 (semantic_cache)！");
                } catch (Exception e) {
                    System.err.println(">>> [跑腿小弟] ❌ 存入高维错题本失败：" + e.getMessage());
                }
            }, asyncExecutor);

            // 任务完成，把档案袋传给汇报官
            return "node_reporter";

        } catch (Exception e) {
            // ==========================================
            // 第三招：【炸膛处理】(ReAct 自愈机制)
            // ==========================================
            String errorMsg = (e.getCause() != null) ? e.getCause().getMessage() : e.getMessage();
            ctx.addLog("❌ 炸膛了！(SQL执行失败)，数据库老师骂道: " + errorMsg);

            ctx.setLastError(errorMsg);
            ctx.setRetryCount(ctx.getRetryCount() + 1);

            // 熔断防死锁
            if (ctx.getRetryCount() > 3) {
                ctx.setFinalAnswer("对不起老板，翻译官重写了3次SQL都报错了。最后的死因：" + errorMsg);
                ctx.addLog("⚠️ 警告：重试次数超限，任务强制终止！");
                return null;
            }

            ctx.addLog(">>> 行刑官决定再给翻译官一次机会，把错题扔回 SqlGenNode 重试...");
            return "node_sql_gen"; // 打回重做
        }
    }
}
