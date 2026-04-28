package com.king.nexus.domain;

import lombok.Data;

/**
 * LLM 结构化输出强约束实体 (Structured CoT Response DTO)
 *
 * <p>核心职责：定义大语言模型 (LLM) 推理输出的标准数据协议 (Schema)，实现自然语言到结构化指令的强类型转换。
 * <p>架构语义：依赖严格的 JSON 格式约束与 AST反序列化 机制完成映射。若反序列化失败（如非标准 JSON 逃逸），
 * 将基于底层重试策略触发 ReAct容错 机制，重新进行上下文编排与生成。本 DTO 实例在无状态请求流中流转，
 * 原生适配高并发隔离场景的数据传递。
 */
@Data // 依托 Lombok 编译期织入生成 Getter/Setter 等基础方法，维持底层数据载体的极简性与高可维护性。
public class SqlGenResponse {

    /**
     * 链式思考 (Chain of Thought, CoT) 推理过程挂载区
     *
     * <p>设计目的：强制大语言模型在生成终态指令前，显式输出意图识别与逻辑拆解的过程
     * （例如结合向量检索注入的 Schema 字典进行表关联与状态过滤决策）。
     * <p>业务价值：通过思维链路的前置约束，大幅降低大模型的逻辑幻觉 (Hallucination) 概率；
     * 同时，该字段可输出至调用链路监控日志或 APM 系统，提供高度白盒化的可解释性与审计追踪能力。
     */
    private String thoughtProcess;

    /**
     * 动态安全审计与防御探针 (Safety Audit Probe)
     *
     * <p>设计目的：利用 LLM 自身进行首道非读操作 (Non-Read Operation) 风险拦截。
     * 明确约束：当模型识别生成的 SQL 包含破坏性或非幂等语句（如 DELETE、DROP、UPDATE 等）时，必须赋值为 false；
     * 仅当确认为纯粹的只读查询（SELECT）时，方可赋值为 true。
     * <p>业务价值：供下游执行网关消费。当网关侦测到 false 状态时将直接阻断执行流转，触发熔断降级策略，严禁异常指令下行至物理数据源。
     */
    private boolean Safe;

    /**
     * 终态可执行指令负载 (Final Executable Query Payload)
     *
     * <p>设计目的：承载剔除所有自然语言冗余与推导过程后的纯净标准 SQL 语法树文本（例如："SELECT * FROM t_orders"）。
     * <p>业务价值：作为最终的指令实体注入至工作流节点的上下文中，直接驱动底层持久化层组件完成物理层面的数据操作。
     */
    private String finalSql;

}
