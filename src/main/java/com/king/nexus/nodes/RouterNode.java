package com.king.nexus.nodes;

import com.king.nexus.engine.Node;
import com.king.nexus.engine.NodeContext;
import com.king.nexus.nodes.strategy.IntentStrategy;
import com.king.nexus.service.LlmService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 👑 帝国首席分发官 (RouterNode - 策略模式重构版)
 * 作用：判断老板的意图，并把任务交给对口的部门。
 * 绝招：没有一个 if-else。全靠下面那支 Spring 自动集结的 strategy 雇佣兵队伍。
 */
@Component
public class RouterNode implements Node {

    @Autowired
    private LlmService llmService;

    // 【核弹级黑科技】：Spring 会自动把所有实现了 IntentStrategy 接口的类，
    // 全部塞进这个 List 里！你以后加新意图，这里一行代码都不用改！
    @Autowired
    private List<IntentStrategy> strategies;

    @Override
    public String getId() {
        return "node_router";
    }

    @Override
    public String execute(NodeContext ctx) {
        String userQuery = ctx.getOriginalQuery();
        ctx.addLog("RouterNode: 正在分析用户意图 -> " + userQuery);

        // 1. 调用大模型，判断意图 (Prompt 保持不变)
        String prompt = """
                        你是一个智能意图识别助手。
                用户的输入是："%s"
                
                请判断用户的意图，并严格遵守以下规则返回结果：
                1. 如果用户想查询数据、统计报表、看销量等，返回 "DATA"。
                2. 如果用户只是闲聊、打招呼、问常识，返回 "CHAT"。
                
                注意：
                - 只能返回 "DATA" 或 "CHAT"。
                - 不要返回任何标点符号。
                - 不要解释原因。
                """.formatted(userQuery);

        String intent = llmService.chat(prompt).trim();
        ctx.addLog("RouterNode: AI 判断意图为 -> [" + intent + "]");

        // ==========================================
        // 2. 策略模式分发 (彻底消灭 if-else)
        // 婴儿级解释：拿着大模型给的意图，去队伍里挨个问：“谁能接这个活？”
        // ==========================================
        for (IntentStrategy strategy : strategies) {
            if (strategy.supports(intent)) {
                // 找到了能干活的兵种，直接问他下一步去哪
                String nextNode = strategy.getNextNodeId();
                ctx.addLog(">>> 已匹配到对应策略，流转至：" + nextNode);
                return nextNode;
            }
        }

        // 3. 兜底机制 (防爆盾)
        // 如果大模型发疯，返回了一个我们没见过的意图，就默认把他当成闲聊处理，防止系统卡死。
        ctx.addLog("⚠️ 警告：未匹配到已知意图，触发兜底机制，流转至闲聊节点。");
        return "node_chat";
    }
}
