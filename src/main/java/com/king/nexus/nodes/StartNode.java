package com.king.nexus.nodes;

import com.king.nexus.engine.Node;
import com.king.nexus.engine.NodeContext;
import org.springframework.stereotype.Component;

@Component
public class StartNode implements Node{
    @Override
    public String getId(){
        return "node_start";
    }
    @Override
    public String execute(NodeContext ctx){
        ctx.addLog("StartNode：正在初始化上下文...");
        ctx.addLog("StartNode：暂时没有后续节点，任务结束。");
        return "node_router";
    }

}
