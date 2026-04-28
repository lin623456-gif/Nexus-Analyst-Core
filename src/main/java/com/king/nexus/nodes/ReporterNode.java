package com.king.nexus.nodes;

import com.king.nexus.engine.Node;
import com.king.nexus.engine.NodeContext;
import com.king.nexus.service.LlmService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 👑 帝国首席汇报官 (ReporterNode)
 * 作用：拿着行刑官(SqlExecNode)缴获的战利品(查询结果)，写成一份漂亮的奏折，念给老板听。
 * 绝招：能看懂各种奇葩的数据库结果（比如空记录、null值），并用优雅的人话包装它。
 */
@Component
public class ReporterNode implements Node {

    // 汇报官的大脑 (用来把干巴巴的数据，变成高情商的废话)
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
        // 1. 检查战利品 (数据清洗)
        // 婴儿级解释：有时候行刑官去数据库转了一圈，什么都没抢到。或者只抢到了一个写着“无”的空盒子。
        // 汇报官必须提前看穿这一切，不能拿着空盒子去忽悠大模型，大模型会变傻的。
        // ==========================================

        List<Map<String, Object>> result = ctx.getQueryResult();
        String dataString; // 这是我们要念给大模型听的“战果摘要”

        // 场景 A：真没查到数据 (结果集是空的)
        if (result == null || result.isEmpty()) {
            dataString = "【系统提示：数据库中未查询到任何匹配的数据记录。】";
            ctx.addLog(">>> 汇报官发现：战利品箱子是空的。");
        }
        // 场景 B：查到了数据，但全是 null (比如 SELECT SUM(amount) 但是昨天没卖出苹果)
        else if (isAllValuesNull(result)) {
            dataString = "【系统提示：查询成功，但统计结果为 null (即相关指标为 0 或空)。】";
            ctx.addLog(">>> 汇报官发现：虽然抢到了箱子，但里面全是空气 (null)。");
        }
        // 场景 C：满载而归 (真查到数据了)
        else {
            // 把数据库的 List<Map> 直接变成字符串，大模型看得懂这种结构。
            dataString = result.toString();
            ctx.addLog(">>> 汇报官整理战利品完毕，准备交给 AI 润色。");
        }

        // ==========================================
        // 2. 写奏折草稿 (组装 Prompt)
        // 婴儿级解释：告诉大模型老板问了什么，我们查到了什么，让大模型帮我们组织语言。
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
        // 3. 呈递奏折 (调用 LLM 生成最终回答)
        // 注意：这里调用的是不需要历史记忆的 chat 方法。因为数据汇报只需要针对当前问题。
        // ==========================================
        String finalAnswer = llmService.chat(prompt);

        // 把最终的漂亮话，装进档案袋的 finalAnswer 里，准备下发给 Controller
        ctx.setFinalAnswer(finalAnswer);

        ctx.addLog("✅ 奏折已写好，即将呈递给老板。");

        // 汇报官是流水线的最后一站，没有下一个将军了，直接返回 null 结束整个流程。
        return null;
    }

    // ---------------------------------------------------------
    // 辅助工具人方法 (Private Methods)
    // ---------------------------------------------------------

    /**
     * 火眼金睛：检查是不是所有的值都是 null
     * 婴儿级解释：比如查 SUM() 没查到，数据库会返回 [{SUM(amount)=null}]，这其实等于没查到。
     */
    private boolean isAllValuesNull(List<Map<String, Object>> result) {
        // 如果只有一行数据
        if (result.size() == 1) {
            Map<String, Object> row = result.get(0);
            // 遍历这一行的所有列
            for (Object value : row.values()) {
                // 只要有一个列不是 null，就说明真的有数据
                if (value != null) {
                    return false;
                }
            }
            // 如果全看完了都是 null，那就说明是个空壳子
            return true;
        }
        return false;
    }
}