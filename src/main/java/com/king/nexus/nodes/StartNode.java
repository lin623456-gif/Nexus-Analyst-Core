package com.king.nexus.nodes;

import com.king.nexus.engine.Node;
import com.king.nexus.engine.NodeContext;
import org.springframework.stereotype.Component;

/**
 * 工作流起始执行节点 (StartNode)
 *
 * <p>路由定位：作为整个图调度引擎 (Graph Engine) 的入口起步算子，负责触发全局的上下文编排。
 * <p>核心职责：执行基础请求生命周期的状态初始化操作，并将控制权流转至意图识别节点。
 * 本节点作为一个无状态的轻量级起始锚点，原生适配高并发隔离场景下的并发流转调度。
 */
@Component
public class StartNode implements Node{
    
    /**
     * 获取当前算子在图执行引擎中的全局唯一寻址标识。
     *
     * @return 注册标识符 "node_start"，作为整个 DAG 执行流的初始触发点
     */
    @Override
    public String getId(){
        return "node_start";
    }
    
    /**
     * 执行工作流入口的上下文装载与初始化流程。
     *
     * <p>处理逻辑：
     * 完成上下文环境的预热挂载，并静态路由至下游策略路由器 (RouterNode) 进行意图识别，
     * 从而启动后续涵盖 向量检索、AST反序列化 以及 ReAct容错 等高阶核心业务算子的调度链路。
     *
     * @param ctx 当前请求的完整执行上下文结构体透传载体
     * @return 下游策略路由分发节点的标识 "node_router"
     */
    @Override
    public String execute(NodeContext ctx){
        ctx.addLog("StartNode：正在初始化上下文...");
        ctx.addLog("StartNode：暂时没有后续节点，任务结束。");
        return "node_router";
    }

}
