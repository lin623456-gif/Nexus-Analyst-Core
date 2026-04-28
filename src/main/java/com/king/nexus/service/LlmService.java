package com.king.nexus.service;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 大语言模型推理核心服务 (LlmService)
 *
 * <p>核心职责：封装底层 LangChain4j 原生接口，提供统一的大语言模型 (LLM) RPC 交互端点。
 * <p>技术特性：
 * 1. 负责多轮会话的上下文编排 (Context Orchestration)，动态装配多角色消息链，解决长文本记忆对齐问题。
 * 2. 作为中枢基建，横向支撑上游算子的意图识别、向量检索前置推断以及面向 AST反序列化 的结构化 Prompt 构建。
 * 3. 在异常态势下，为系统级的 ReAct容错 回路提供智能推理算力支撑；自身调用链路底层兼容高并发隔离场景。
 */
@Service
public class LlmService {

    // 底层大语言模型客户端代理实例，由 Spring 容器依据配置动态装配注入
    private final ChatLanguageModel chatModel;

    public LlmService(ChatLanguageModel chatModel) {
        this.chatModel = chatModel;
        System.out.println(">>> 👑 帝国中央大脑 (LLM) 已连接，随时待命。");
    }

    // ==========================================
    // 核心接口 1：状态补偿模式推理 (Stateful Inference with Context)
    // 动态融合当前请求载荷与历史缓存链路数据，执行标准的消息对象重塑 (Message Remodeling)。
    // 从而构建结构化的上下文窗口，避免多轮流转过程中的语义断层。
    // ==========================================
    /**
     * 执行带历史状态补偿的深度上下文推理。
     *
     * @param currentPrompt 当前请求的自然语言指令或结构化查询载荷
     * @param historyList   从分布式缓存中反序列化提取的历史会话轨迹集合 (支持 nullable)
     * @return LLM 执行多模态对齐推理后生成的文本终态负载
     */
    public String chat(String currentPrompt, List<String> historyList) {

        System.out.println("\n====== [中央大脑] 正在向神明发送祈祷 ======");

        // 1. 初始化消息编排管道。
        // 底层模型 API 依赖强类型的 ChatMessage 实体集合，需进行非结构化字符串的转换与包装。
        List<ChatMessage> messages = new ArrayList<>();

        // 2. 注入全局系统级预设指令 (SystemMessage)
        // 对底层大模型执行元提示 (Meta-Prompt) 边界约束与角色对齐。
        messages.add(SystemMessage.from("你是一个全能的智能数据助手，名为 Nexus。请根据上下文，简明扼要地回答用户的问题。"));

        // 3. 执行历史状态上下文补偿，遍历反序列化后的缓存列表并映射为多角色消息结构。
        if (historyList != null && !historyList.isEmpty()) {
            for (String hist : historyList) {
                // 判定当前行迹标识为用户输入 (User Context)
                if (hist.startsWith("User: ")) {
                    // 剥离序列化前缀，构建用户态消息体追加至编排管道
                    messages.add(UserMessage.from(hist.substring(6)));
                }
                // 判定当前行迹标识为模型输出响应 (AI Context)
                else if (hist.startsWith("AI: ")) {
                    // 剥离序列化前缀，构建助手态消息体追加至编排管道
                    messages.add(AiMessage.from(hist.substring(4)));
                }
            }
            System.out.println(">>> 🧠 大脑已成功注入 " + historyList.size() + " 条前世记忆！");
        } else {
            System.out.println(">>> 🧠 大脑发现这是个新客人，没有历史记忆。");
        }

        // 4. 将当前周期的最新请求载荷作为叶子节点追加至消息总线尾部。
        messages.add(UserMessage.from(currentPrompt));

        // 5. 触发底层的远程过程调用 (RPC) 提交计算图。
        // 传递强类型消息体集合进行多路注意力计算及文本生成。
        String response = chatModel.generate(messages).content().text();

        System.out.println("====== [中央大脑] 神明降下神谕 ======\n" + response);

        // 6. 提取并抛出模型生成的纯文本负载至上游调用链节点。
        return response;
    }

    // ==========================================
    // 核心接口 2：无状态单次推理 (Stateless Zero-Shot Inference)
    // 提供无需多轮上下文补偿的瞬态计算支持，利用方法重载机制旁路历史编排链路。
    // ==========================================
    /**
     * 无状态单次推理接口。
     *
     * <p>适用于自包含的指令运算场景。例如：意图识别的零样本分类、基于向量检索结果组装的单次分析、
     * 以及面向下游 AST反序列化 提取的 JSON Schema 生成等高原子性操作。
     *
     * @param singlePrompt 独立且完整的单次指令模板载荷
     * @return LLM 响应的纯文本载荷
     */
    public String chat(String singlePrompt) {
        // 方法重载委托调用，显式置空历史状态参数以旁路状态补偿流程，降低计算开销并提升复用性。
        return chat(singlePrompt, null);
    }
}
