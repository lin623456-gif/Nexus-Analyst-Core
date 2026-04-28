package com.king.nexus.controller;

import com.king.nexus.engine.GraphEngine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api")
// 【核弹防御】：解决跨域问题。如果不加这个，等会你自己写的 HTML 页面会连不上这个接口！
@CrossOrigin(origins = "*")
public class ChatController {

    @Autowired
    private GraphEngine graphEngine;

    // ==========================================
    // 接口一：【老旧的同步接口】(保留做测试用)
    // ==========================================
    @PostMapping("/chat")
    public CompletableFuture<Map<String, Object>> chat(@RequestBody Map<String, String> request) {
        String query = request.get("query");
        System.out.println("\n====== [Web 大门] 收到客官请求 (普通模式): " + query + " ======");
        return graphEngine.run(query).thenApply(result -> Map.of(
                "answer", result.getFinalAnswer(),
                "logs", result.getLogs()
        ));
    }

    // ==========================================
    // 接口二：【全新的流式接口】(SSE 直播模式)
    // 婴儿级解释：
    // 1. GET 请求更适合 SSE，所以参数带在 URL 里：/api/stream/chat?query=查销量
    // 2. 返回值是 SseEmitter，这是 Spring 提供的一根“单向水管”。
    // ==========================================
    @GetMapping(value = "/stream/chat", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter streamChat(@RequestParam String query) {

        System.out.println("\n====== [Web 大门] 收到客官请求 (直播模式): " + query + " ======");

        // 1. 建立管道！参数 0L 代表永不超时。
        // 如果你不传 0L，Spring 默认 30 秒就会强制掐断管子，大模型如果思考太久就会报错。
        SseEmitter emitter = new SseEmitter(0L);

        // 2. 把管道的一头交给总司令部 (GraphEngine)，并告诉它怎么用这根管子。
        graphEngine.runStream(
                query,

                // 按钮1：当将军们有话要说 (onMessage) 时怎么做？
                (message) -> {
                    try {
                        // 把话塞进管子里，推给浏览器
                        emitter.send(message);
                    } catch (Exception e) {
                        // 如果塞不进去(比如用户把网页关了)，就挂断电话
                        emitter.completeWithError(e);
                    }
                },

                // 按钮2：当将军们干完活 (onComplete) 时怎么做？
                () -> {
                    // 彻底关闭管道，告诉浏览器“直播结束”
                    emitter.complete();
                },

                // 按钮3：当车间爆炸 (onError) 时怎么做？
                (error) -> {
                    // 告诉浏览器“系统崩溃，直播中断”
                    emitter.completeWithError(error);
                }
        );

        // 3. 【光速响应】：把管道的另一头立刻扔给浏览器！
        // 此时，Tomcat 主线程下班！剩下的直播推流，全靠后台的虚拟线程去推！
        return emitter;
    }
}
