package com.king.nexus.nodes;

import com.king.nexus.engine.Node;
import com.king.nexus.engine.NodeContext;
import com.king.nexus.service.LlmService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 结构化数据转自然语言生成节点 (ReporterNode)
 *
 * <p>路由定位：作为数据查询工作流的末端叶子节点，接收上游 SQL 执行节点输出的结构化数据集，
 * 通过 Prompt Engineering 将原始数据转化为符合业务语境的自然语言摘要 (NLG)。
 * <p>核心能力：对上游传递的数据集进行空集检测与空值清洗，确保输入至大语言模型 (LLM) 的有效载荷具有语义确定性，
 * 避免因空参导致模型生成幻觉。
 * <p>生命周期：输出终态回答后挂载至 {@link NodeContext#finalAnswer}，并向调度引擎返回 null 终止后续流转。
 */
@Component
public class ReporterNode implements Node {

    // 大语言模型推理服务接入句柄，负责 Prompt 封装与远程推理调用
    @Autowired
    private LlmService llmService;

    @Override
    public String getId() {
        return "node_reporter";
    }

    @Override
    public String execute(NodeContext ctx) {
        ctx.addLog("ReporterNode : 汇报官就位，正在将战利品写成奏折...");

        // ==========================================
        // 1. 数据有效性预处理 (Data Sanitization & Cleanse)
        // 对上游查询结果进行反序列化校验，封装空集语义，避免无效载荷注入提示词工程
        // ==========================================

        List<Map<String, Object>> result = ctx.getQueryResult();
        String dataString; // 将作为结构化摘要注入 Prompt 的序列化数据载荷

        // 场景 A：查询结果集为空 (Empty ResultSet)
        if (result == null || result.isEmpty()) {
            dataString = "【系统提示：数据库中未查询到任何匹配的数据记录。】";
            ctx.addLog(">>> 汇报官发现：战利品箱子是空的。");
        }
        // 场景 B：查询结果仅含空值指标 (All-columns Null)
        else if (isAllValuesNull(result)) {
            dataString = "【系统提示：查询成功，但统计结果为 null (即相关指标为 0 或空)。】";
            ctx.addLog(">>> 汇报官发现：虽然抢到了箱子，但里面全是空气 (null)。");
        }
        // 场景 C：有效数据提取 (Valid Payload)
        else {
            // 将 List<Map> 结构直接序列化为字符串，LLM 可基于此进行上下文理解
            dataString = result.toString();
            ctx.addLog(">>> 汇报官整理战利品完毕，准备交给 AI 润色。");
        }

        // ==========================================
        // 2. 组装领域特定 Prompt (Domain Prompt Assembly)
        // 将原始查询意图与清洗后的数据负载按业务角色模板进行拼接
        // ==========================================

        String prompt = """
                你是一个专业、严谨且高情商的商业数据分析师。
                
                【老板的原话】：
                "%s"
                
                【底层数据库返回的真实数据】：
                %s
                
                【你的任务与要求】：
                1. 请根据上述【真实数据】，直接且准确地回答老板的问题。
                2. 语气要专业、简洁，不要像机器人一样生硬。
                3. 如果数据提示“未查询到”或“结果为 null”，请委婉地告诉老板“很抱歉，没有找到相关数据”或“该指标当前为 0”。
                4. **绝对禁止** 在回答中出现任何关于 SQL 怎么写、数据库表结构长什么样的废话！老板只关心结果！
                """.formatted(ctx.getOriginalQuery(), dataString);

        // ==========================================
        // 3. 结果生成与上下文挂载 (NLG Execution & State Commit)
        // 注：此处调用无需历史上下文的单次推理，因数据汇报仅依赖当前查询结果
        // ==========================================
        String finalAnswer = llmService.chat(prompt);

        // 将终态回答写入上下文，供调度引擎进行 SSE 推送与归档
        ctx.setFinalAnswer(finalAnswer);

        ctx.addLog("✅ 奏折已写好，即将呈递给老板。");

        // 当前节点为工作流终点，返回 null 终止图调度
        return null;
    }

    // ---------------------------------------------------------
    // 辅助校验方法 (Integrity Checks)
    // ---------------------------------------------------------

    /**
     * 全列空值检测器 (Null-only Row Validator)
     *
     * <p>用于验证单行结果集中是否所有列值均为 {@code null}，典型场景如聚合查询无匹配行时返回的空壳指标。
     *
     * @param result 上游运算符输出的查询结果集
     * @return 若结果集仅包含一行且该行所有列的值都为 null，则返回 true
     */
    private boolean isAllValuesNull(List<Map<String, Object>> result) {
        // 仅当结果集精确包含一行时才进行全列扫描
        if (result.size() == 1) {
            Map<String, Object> row = result.get(0);
            // 遍历行内全部 value，检验是否存在非 null 的实质性数据
            for (Object value : row.values()) {
                // 存在任一非空字段即判定为有效数据
                if (value != null) {
                    return false;
                }
            }
            // 所有字段均为 null，判定为语义上的空壳数据
            return true;
        }
        return false;
    }
}
