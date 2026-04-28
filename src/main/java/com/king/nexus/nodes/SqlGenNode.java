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
 * SQL 生成与结构化推理节点 (Structured CoT JSON 约束版)
 *
 * <p>核心职责：基于大语言模型 (LLM) 将自然语言查询转换为合法的 PostgreSQL 语句，并通过多级缓存和混合检索策略优化推理效率与准确性。
 * <p>技术特性：
 * <ol>
 *   <li>强类型 JSON 反序列化 (AST 约束)：彻底摒弃脆弱的纯文本规则提取，通过 {@link SqlGenResponse} 实体对 LLM 输出进行结构化约束校验。</li>
 *   <li>链式思考 (Chain of Thought, CoT)：强制模型输出 {@code thoughtProcess} 字段以暴露底层推导逻辑，显著降低幻觉率并提高意图识别透明度。</li>
 *   <li>内建安全审计探针 ({@code isSafe})：由模型自我判定 SQL 是否包含写操作，实现前置安全熔断。</li>
 *   <li>语义缓存与混合 RAG 检索：结合向量检索与关键词匹配，优先复用已验证的高置信度查询，降低 LLM 调用开销。</li>
 *   <li>ReAct 容错与自愈回路：在 JSON 解析失败或 SQL 执行异常时，将错误上下文反馈至模型驱动修正重试，并内置最大重试次数熔断。</li>
 * </ol>
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

    /**
     * 执行 SQL 生成与结构化调度流程。
     *
     * <p>处理流程包含以下阶段：
     * <ol>
     *   <li>查询向量化并检查语义缓存，命中则直接写入 SQL 并跳转至执行节点。</li>
     *   <li>通过混合检索（关键词 + 向量）获取相关表 DDL，构建 RAG 上下文。</li>
     *   <li>组装强约束 JSON 模板 Prompt，强制 LLM 输出 {@link SqlGenResponse} 结构体。</li>
     *   <li>对 LLM 返回进行反序列化校验，若失败或安全探针触发则进入 ReAct 自愈循环。</li>
     * </ol>
     *
     * @param ctx 当前请求的完整执行上下文，包含原始查询、重试计数、错误栈等
     * @return 下游节点标识 (node_sql_exec / node_sql_gen / null 表终止)
     */
    @Override
    public String execute(NodeContext ctx) {
        String query = ctx.getOriginalQuery();
        ctx.addLog("SqlGenNode : 翻译官就位，开启结构化心智模式...");

        // 0. 查询文本向量化：为语义缓存检索和混合 RAG 提供输入向量
        float[] queryVectorArray = embeddingModel.embed(query).content().vector();
        PGvector pgQueryVector = new PGvector(queryVectorArray);

        // ==========================================
        // 阶段一：语义缓存查询 (Semantic Cache Lookup)
        // 利用向量相似度检索历史成功 SQL，实现快速路径，避免重复推理
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
        // 阶段二：混合检索 (Hybrid RAG) - 关键词精确匹配 + 向量语义近似
        // 构建供 LLM 推理使用的实时 Schema 上下文
        // ==========================================
        ctx.addLog(">>> 启动【混合 RAG 检索】...");
        String finalSchema = retrieveSchemaHybrid(query, pgQueryVector, ctx);

        if (finalSchema == null || finalSchema.isEmpty()) {
            ctx.setLastError("兵器库是空的，找不到任何表结构！");
            ctx.setFinalAnswer("对不起老板，帝国的数据库里没有配置任何表结构信息。");
            return null;
        }

        // ==========================================
        // 阶段三：构建结构化 JSON 约束 Prompt
        // 采用 CoT + JSON Schema 强制约束，要求模型填充预定义实体而非自由文本
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
        // 阶段四：LLM 调用与强类型反序列化 (AST反序列化)
        // 对模型返回进行安全清洗、实体映射及熔断校验
        // ==========================================
        String rawResponse = llmService.chat(prompt).trim();

        // 对残余 Markdown 标记进行兜底正则清除，保障 JSON 解析器不受干扰
        if (rawResponse.startsWith("```json")) {
            rawResponse = rawResponse.replaceAll("^```json\\n?", "").replaceAll("```$", "").trim();
        }

        try {
            // 将 LLM 输出反序列化为强类型实体，实现结构校验与字段提取
            SqlGenResponse responseObj = JSON.parseObject(rawResponse, SqlGenResponse.class);

            // 1. 输出模型思维链 (CoT)，支持审计与可解释性
            ctx.addLog("💡 AI 思考过程：" + responseObj.getThoughtProcess());

            // 2. 安全探针校验：若模型自检发现写操作，立即触发熔断终止
            if (!responseObj.isSafe()) {
                ctx.addLog("🚨 警报！AI 判定此操作包含高危指令 (DELETE/UPDATE等)，已强制拦截！");
                ctx.setLastError("检测到高危安全操作，已被系统拦截。");
                ctx.setFinalAnswer("抱歉，我不能为您执行具有破坏性的数据库操作。");
                return null;
            }

            // 3. 提取终态 SQL 负载
            String finalSql = responseObj.getFinalSql();
            if ("CANNOT_ANSWER".equals(finalSql)) {
                ctx.setLastError("AI 认为现有的表结构无法解答此问题。");
                ctx.setFinalAnswer("抱歉，虽然我找到了相关的表，但我当前掌握的数据逻辑无法回答您的问题。");
                return null;
            }

            ctx.addLog("AI 最终生成的 SQL：[" + finalSql + "]");
            ctx.setGeneratedSql(finalSql);

            // 成功生成，路由至 SQL 执行节点
            return "node_sql_exec";

        } catch (Exception e) {
            // JSON 反序列化失败：进入 ReAct 自愈回路
            // 将异常信息注入错误上下文，触发节点自重试
            ctx.addLog("❌ 大模型未遵守 JSON 格式输出军规，解析失败: " + e.getMessage());
            ctx.setLastError("你输出的格式不是合法的 JSON，无法解析。请严格按照模板输出！");

            ctx.setRetryCount(ctx.getRetryCount() + 1);
            if (ctx.getRetryCount() > 3) {
                ctx.setFinalAnswer("对不起老板，大模型精神错乱，无法生成合法格式的数据。");
                return null;
            }
            // 回环至本节点重新执行，基于错误提示进行修复
            return "node_sql_gen";
        }
    }

    // ---------------------------------------------------------
    // 混合检索器：融合关键词精确匹配与向量语义匹配
    // ---------------------------------------------------------
    /**
     * 混合检索器：基于关键词精确匹配与向量语义相似度的双层召回策略。
     *
     * <p>第一层：遍历所有表的 {@code keywords} 字段，若用户查询包含任意关键词则直接命中。
     * <p>第二层：对用户查询向量进行语义近似检索，补充未被关键词覆盖的候选表。
     * 最终返回去重合并后的 DDL 文本，作为 LLM 的上下文输入。
     *
     * @param userQuery 用户原始输入
     * @param pgQueryVector 用户查询的向量化表示
     * @param ctx 当前请求上下文，用于记录检索日志
     * @return 拼接后的表结构 DDL 字符串
     */
    private String retrieveSchemaHybrid(String userQuery, PGvector pgQueryVector, NodeContext ctx) {
        StringBuilder retrievedSchema = new StringBuilder();
        Set<String> addedTables = new HashSet<>();
        int exactHit = 0;
        int semanticHit = 0;

        // 关键词精确匹配阶段
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

        // 向量语义匹配阶段
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
