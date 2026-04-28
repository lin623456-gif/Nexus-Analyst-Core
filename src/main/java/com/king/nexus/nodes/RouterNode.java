package com.king.nexus.nodes;

import com.king.nexus.engine.Node;
import com.king.nexus.engine.NodeContext;
import com.king.nexus.nodes.strategy.IntentStrategy;
import com.king.nexus.service.LlmService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 意图识别与策略路由节点 (RouterNode - 策略模式解耦实现)
 *
 * <p>路由定位：基于大语言模型 (LLM) 的语义分析结果，将用户输入动态分发至对应的处理子流程。
 * 采用纯策略模式消除条件分支，所有具体路由策略均以 {@link IntentStrategy} 实现类的形式
 * 由 Spring 容器自动装配，实现路由表的热插拔扩展。
 * <p>容错机制：当 LLM 产生未定义的意图标签时，将触发降级兜底，默认路由至泛闲聊处理节点，
 * 防止工作流因意图识别失败而终止。
 */
@Component
public class RouterNode implements Node {

    @Autowired
    private LlmService llmService;

    /**
     * 意图策略清单：Spring 容器自动扫描并注入所有 {@link IntentStrategy} 实现，
     * 后续新增意图策略无需修改路由器内部代码，符合开闭原则。
     */
    @Autowired
    private List<IntentStrategy> strategies;

    @Override
    public String getId() {
        return "node_router";
    }

    /**
     * 执行意图识别与策略路由分发。
     *
     * <p>处理流程：
     * <ol>
     *   <li>构造意图识别 Prompt，调用 LLM 进行语义归类，目标输出为 <code>"DATA"</code> 或 <code>"CHAT"</code>。</li>
     *   <li>遍历注入的策略集合，基于 {@link IntentStrategy#supports(String)} 匹配首个可处理该意图的策略。</li>
     *   <li>返回策略指定的下游节点标识，供调度引擎继续上下文编排。</li>
     *   <li>若无匹配策略，执行降级逻辑，默认路由至 <code>"node_chat"</code> 以防流转中断。</li>
     * </ol>
     *
     * @param ctx 当前请求的完整执行上下文
     * @return 下游目标节点标识，或降级节点 <code>"node_chat"</code>
     */
    @Override
    public String execute(NodeContext ctx) {
        String userQuery = ctx.getOriginalQuery();
        ctx.addLog("RouterNode: 正在分析用户意图 -> " + userQuery);

        // 1. 构造意图识别 Prompt，通过 LLM 进行语义二分类
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
        // 2. 策略匹配与动态路由：遍历策略集合，执行意图与处理器的匹配
        // 匹配成功即返回对应下游节点标识，实现无分支的意图分发
        // ==========================================
        for (IntentStrategy strategy : strategies) {
            if (strategy.supports(intent)) {
                String nextNode = strategy.getNextNodeId();
                ctx.addLog(">>> 已匹配到对应策略，流转至：" + nextNode);
                return nextNode;
            }
        }

        // 3. 容错降级处理：当 LLM 返回未注册意图标签时，默认路由至泛闲聊处理节点，
        // 避免意图识别阶段的异常导致全链路终止
        ctx.addLog("⚠️ 警告：未匹配到已知意图，触发兜底机制，流转至闲聊节点。");
        return "node_chat";
    }
}
