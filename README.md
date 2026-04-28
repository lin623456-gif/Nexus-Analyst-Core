# Nexus-Analyst-Core
企业级 Agentic Workflow 智能数据分析引擎底座
graph TD
    %% --- 用户入口 ---
    User((User))
    API[API Layer: SSE Controller]
    
    %% --- 核心引擎 ---
    Engine[Graph Engine: 图执行引擎]
    Context[NodeContext: 上下文 & 记忆]
    Log[Execution Log: 执行日志]
    
    %% --- 业务节点 ---
    Router[Router Node: 意图路由]
    Chat[Chat Node: 闲聊]
    
    %% --- 数据分析链路 ---
    Retriever[Retrieval Node: RAG检索]
    SqlGen[SQL Gen Node: 生成SQL]
    SqlExec[SQL Exec Node: 执行SQL]
    Reflection[Reflection Node: 自愈反思]
    Reporter[Reporter Node: 结果润色]
    
    %% --- 基础设施 ---
    VectorDB[(Vector DB: Milvus)]
    MySQL[(MySQL: 业务库)]
    LLM[LLM Service: DeepSeek]
    
    %% --- 连线逻辑 ---
    User -- "1. 提问 (HTTP Stream)" --> API
    API -- "2. 启动任务" --> Engine
    
    Engine -- "3. 初始化" --> Context
    Engine -- "4. 调度" --> Router
    
    Router -- "意图: 闲聊" --> Chat
    Chat -- "调用" --> LLM
    Chat -- "输出" --> Engine
    
    Router -- "意图: 查数" --> Retriever
    Retriever -- "检索Schema" --> VectorDB
    Retriever -- "Schema信息" --> SqlGen
    
    SqlGen -- "Prompt + Schema" --> LLM
    SqlGen -- "生成的SQL" --> SqlExec
    
    SqlExec -- "执行SQL" --> MySQL
    
    %% --- 自愈闭环 ---
    SqlExec -- "报错!" --> Reflection
    Reflection -- "错误堆栈 + 旧SQL" --> SqlGen
    
    %% --- 成功链路 ---
    SqlExec -- "数据结果" --> Reporter
    Reporter -- "生成报告" --> LLM
    
    %% --- 最终输出 ---
    Reporter -- "Final Answer" --> Engine
    Chat -- "Final Answer" --> Engine
    
    Engine -- "5. 实时推送 (SSE)" --> API
    Engine -- "6. 异步落库" --> Log
    API -- "7. 响应" --> User
