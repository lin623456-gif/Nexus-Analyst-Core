package com.king.nexus.nodes;

import com.alibaba.fastjson2.JSON;
import com.king.nexus.domain.SqlGenResponse;
import com.king.nexus.engine.Node;
import com.king.nexus.engine.NodeContext;
import com.king.nexus.service.LlmService;
import com.king.nexus.utils.RedisUtils;
import com.pgvector.PGvector;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 👑 帝国首席数据翻译官 (Structured CoT 脑部手术版)
 * 绝招：
 * 1. 废除了肮脏的字符串截取，全面拥抱强类型 JSON 反序列化。
 * 2. 强制大模型打草稿 (CoT)，物理级降低幻觉率。
 * 3. 内建大模型自我安全审查 (isSafe 探针)。
 */
@Component
public class SqlGenNode implements Node {

    @Autowired
    private LlmService llmService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RedisUtils redisUtils;

    @Autowired
    private EmbeddingModel embeddingModel;

    @Value("${nexus.feature.semantic-cache.enabled:true}")
    private boolean semanticCacheEnabled;

    @Override
    public String getId() {
        return "node_sql_gen";
    }

    @Override
    public String execute(NodeContext ctx) {
        String query = ctx.getOriginalQuery();
        ctx.addLog("SqlGenNode : 翻译官就位，开启结构化心智模式...");

        // 0. 弹药装填：【人话变数字】
        float[] queryVectorArray = embeddingModel.embed(query).content().vector();
        PGvector pgQueryVector = new PGvector(queryVectorArray);

        // ==========================================
        // 第一招：【语义级抄作业】(Semantic Cache)
        // ==========================================
        if (ctx.getLastError() == null && semanticCacheEnabled) {
            ctx.addLog(">>> 正在语义空间中检索相似的错题本...");
            String cacheSql = "SELECT generated_sql FROM semantic_cache WHERE query_embedding <=> ? < 0.15 ORDER BY query_embedding <=> ? ASC LIMIT 1";
            List<String> cachedSqls = jdbcTemplate.queryForList(cacheSql, String.class, pgQueryVector, pgQueryVector);

            if (!cachedSqls.isEmpty()) {
                ctx.addLog("⚡ 爽！高维空间命中！直接抄作业！");
                ctx.setGeneratedSql(cachedSqls.get(0));
                return "node_sql_exec";
            }
        } else if (!semanticCacheEnabled) {
            ctx.addLog("⚠️ [Feature Toggle] 语义缓存拦截已关闭，强制走大模型生成流程。");
        }

        // ==========================================
        // 第二招：【双管猎枪翻字典】(Hybrid RAG 混合检索)
        // ==========================================
        ctx.addLog(">>> 启动【混合 RAG 检索】...");
        String finalSchema = retrieveSchemaHybrid(query, pgQueryVector, ctx);

        if (finalSchema == null || finalSchema.isEmpty()) {
            ctx.setLastError("兵器库是空的，找不到任何表结构！");
            ctx.setFinalAnswer("对不起老板，帝国的数据库里没有配置任何表结构信息。");
            return null;
        }

        // ==========================================
        // 第三招：【写最高军规】(组装 JSON 约束 Prompt)
        // 婴儿级解释：我们不再让大模型随便写字了。我们塞给它一个 JSON 表格，逼它填空。
        // ==========================================
        String prompt;
        if (ctx.getLastError() == null) {
            prompt = """
                    你是一个极其严谨的 PostgreSQL 数据架构师。
                    请根据以下提供的数据库表结构信息，将用户的自然语言问题转换为 SQL。
                    
                    【我为你找来的表结构】：
                    %s
                    
                    【老板问的问题】：
                    "%s"
                    
                    【最高执行军规 (违令者斩)】：
                    1. 你必须、只能输出一个合法的 JSON 字符串！绝对不允许包含任何 Markdown 标记 (如 ```json) 或其他解释性文本！
                    2. SQL 中的字符串值必须使用单引号，绝对禁止使用双引号 (会破坏 JSON 格式)！
                    3. 业务状态字段 (如 order_status) 必须【一字不差】地匹配表结构 Comment 里的中文！
                    4. 如果用户提到的商品名称可能不完整，必须使用 ILIKE 进行模糊匹配。
                    
                    【强制输出格式 (必须严格遵守此 JSON 结构)】：
                    {
                        "thoughtProcess": "在这里写下你的思考过程：你需要用到哪些表？用什么条件关联？过滤条件是什么？",
                        "safe": true/false (如果 SQL 中包含 DELETE/UPDATE/DROP/INSERT 等写操作，必须填 false，否则填 true),
                        "finalSql": "在这里写下最终的 PostgreSQL 语句。如果无法解答，填 'CANNOT_ANSWER'"
                    }
                    """.formatted(finalSchema, query);
        } else {
            ctx.addLog("⚠️ 触发自愈机制！拿着老师的骂人原话，让大模型改错...");
            prompt = """
                    你生成的 JSON 解析失败，或 SQL 执行报错了。请你修复它。
                    
                    【表结构信息】：
                    %s
                    
                    【你上次生成的错题】：
                    %s
                    
                    【报错信息】：
                    %s
                    
                    【严厉警告】：
                    1. 你的输出必须是一个【绝对合法】的 JSON 字符串，不能包含任何多余字符！
                    2. 检查 SQL 语法是否符合 PostgreSQL 规范。
                    
                    【强制输出格式 (必须严格遵守此 JSON 结构)】：
                    {
                        "thoughtProcess": "你反思了什么？为什么会报错？你怎么改的？",
                        "isSafe": true/false,
                        "finalSql": "修复后的最终 SQL"
                    }
                    """.formatted(finalSchema, ctx.getGeneratedSql(), ctx.getLastError());
        }

        // ==========================================
        // 第四招：【召唤神明，强行拆解神谕】(反序列化与熔断)
        // 婴儿级解释：拿到大模型的 JSON，用代码把它拆开。遇到不听话的直接骂回去。
        // ==========================================
        String rawResponse = llmService.chat(prompt).trim();

        // 暴力清洗小弟的残余工作 (有时候大模型死性不改，还是会带上 ```json，做个简单的正则替换兜底)
        if (rawResponse.startsWith("```json")) {
            rawResponse = rawResponse.replaceAll("^```json\\n?", "").replaceAll("```$", "").trim();
        }

        try {
            // 【核弹级技术】：反序列化！把一坨字符串，瞬间变成一个严丝合缝的 Java 对象！
            SqlGenResponse responseObj = JSON.parseObject(rawResponse, SqlGenResponse.class);

            // 1. 打印大模型的“内心戏”(thoughtProcess)，这在前端展示时会显得极其牛逼
            ctx.addLog("💡 AI 思考过程：" + responseObj.getThoughtProcess());

            // 2. 【安全熔断探针】
            if (!responseObj.isSafe()) {
                ctx.addLog("🚨 警报！AI 判定此操作包含高危指令 (DELETE/UPDATE等)，已强制拦截！");
                ctx.setLastError("检测到高危安全操作，已被系统拦截。");
                ctx.setFinalAnswer("抱歉，我不能为您执行具有破坏性的数据库操作。");
                return null; // 罢工，保护数据库！
            }

            // 3. 提取子弹
            String finalSql = responseObj.getFinalSql();
            if ("CANNOT_ANSWER".equals(finalSql)) {
                ctx.setLastError("AI 认为现有的表结构无法解答此问题。");
                ctx.setFinalAnswer("抱歉，虽然我找到了相关的表，但我当前掌握的数据逻辑无法回答您的问题。");
                return null;
            }

            ctx.addLog("AI 最终生成的 SQL：[" + finalSql + "]");
            ctx.setGeneratedSql(finalSql);

            // 成功拆解，下一站：行刑官
            return "node_sql_exec";

        } catch (Exception e) {
            // 【反序列化失败的 ReAct 兜底】
            // 婴儿级解释：如果大模型脑抽了，没按 JSON 格式输出，Fastjson 就会报错(抛异常)。
            // 没关系，我们把这个异常也当成一种“炸膛”，丢给 ReAct 机制，让他重写！
            ctx.addLog("❌ 大模型未遵守 JSON 格式输出军规，解析失败: " + e.getMessage());
            ctx.setLastError("你输出的格式不是合法的 JSON，无法解析。请严格按照模板输出！");

            ctx.setRetryCount(ctx.getRetryCount() + 1);
            if (ctx.getRetryCount() > 3) {
                ctx.setFinalAnswer("对不起老板，大模型精神错乱，无法生成合法格式的数据。");
                return null;
            }
            // 退回给自己，重新生成
            return "node_sql_gen";
        }
    }

    // ---------------------------------------------------------
    // 【混合检索器】 (保留之前的代码，一字未动)
    // ---------------------------------------------------------
    private String retrieveSchemaHybrid(String userQuery, PGvector pgQueryVector, NodeContext ctx) {
        StringBuilder retrievedSchema = new StringBuilder();
        Set<String> addedTables = new HashSet<>();
        int exactHit = 0;
        int semanticHit = 0;

        String keywordSql = "SELECT table_name, ddl_content, keywords FROM meta_tables_v2";
        try {
            List<Map<String, Object>> allTables = jdbcTemplate.queryForList(keywordSql);
            for (Map<String, Object> row : allTables) {
                String tableName = (String) row.get("table_name");
                String keywordsStr = (String) row.get("keywords");
                String ddl = (String) row.get("ddl_content");

                if (keywordsStr != null) {
                    for (String kw : keywordsStr.split(",")) {
                        if (userQuery.contains(kw.trim())) {
                            retrievedSchema.append("【精准命中: ").append(tableName).append("】:\n").append(ddl).append("\n\n");
                            addedTables.add(tableName);
                            exactHit++;
                            ctx.addLog("    -> 🎯 霰弹枪命中: [" + kw + "]");
                            break;
                        }
                    }
                }
            }
        } catch (Exception e) {
            ctx.addLog("    -> ⚠️ 关键词检索异常，跳过。");
        }

        String vectorSql = "SELECT table_name, ddl_content FROM meta_tables_v2 ORDER BY embedding <=> ? ASC LIMIT 3";
        List<Map<String, Object>> vectorHits = jdbcTemplate.queryForList(vectorSql, pgQueryVector);

        for (Map<String, Object> row : vectorHits) {
            String tableName = (String) row.get("table_name");
            String ddl = (String) row.get("ddl_content");

            if (!addedTables.contains(tableName)) {
                retrievedSchema.append("【高维命中: ").append(tableName).append("】:\n").append(ddl).append("\n\n");
                addedTables.add(tableName);
                semanticHit++;
                ctx.addLog("    -> 🌌 狙击枪命中: [" + tableName + "]");
            }
        }

        ctx.addLog(">>> 混合检索完毕：精准 " + exactHit + " 张，语义 " + semanticHit + " 张。");
        return retrievedSchema.toString();
    }
}
