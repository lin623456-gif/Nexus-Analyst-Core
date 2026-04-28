package com.king.nexus.engine;

public interface Node {
    /**
     *
     * @param ctx
     * @return
     */
    String execute(NodeContext ctx);
    String getId();
}
