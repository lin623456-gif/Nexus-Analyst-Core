package com.king.nexus.utils;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

@Component
public class RedisUtils {

    // ==========================================
    // 1. 我们的工具箱 (装备)
    // ==========================================

    // 这是用来操作 Redis (远程大笔记本) 的笔
    @Autowired
    private StringRedisTemplate redisTemplate;

    // 这是一个跑腿小弟团队 (线程池)。脏活累活都交给他们，不让系统主线程卡住
    private final Executor asyncExecutor = ForkJoinPool.commonPool();

    // 这是我们的大脑极速内存 (Caffeine 本地缓存)
    // 特点：记住最近10分钟的东西，最多记1000个人的，忘了就叫跑腿小弟去 Redis 查。
    private final AsyncCache<String, List<String>> asyncCache = Caffeine.newBuilder()
            .expireAfterWrite(10, TimeUnit.MINUTES) // 10分钟后自动忘掉
            .maximumSize(1000) // 脑容量上限
            .executor(asyncExecutor) // 绑定跑腿小弟
            .buildAsync();

    // 这是一段“魔法咒语”(Lua脚本)。
    // 作用：让 Redis 一口气做完4件事（存用户话、存AI话、剪裁多余记录、设置过期时间）。
    // 为什么用咒语？因为如果一件件做，万一中途断电了，数据就坏了。咒语保证“要么全做完，要么全不做”。
    private static final String ARCHIVE_LUA =
            "redis.call('RPUSH', KEYS[1], ARGV[1]) \n" +      // 动作1：把用户的话塞进列表尾部
                    "redis.call('RPUSH', KEYS[1], ARGV[2]) \n" +      // 动作2：把 AI 的话塞进列表尾部
                    "redis.call('LTRIM', KEYS[1], -tonumber(ARGV[3]), -1) \n" + // 动作3：只保留最后 N 句话
                    "redis.call('EXPIRE', KEYS[1], 3600) \n" +        // 动作4：整个列表续命 1 小时
                    "return 1"; // 搞定收工

    // 准备好这个魔法咒语，返回值是 Long 类型
    private final DefaultRedisScript<Long> archiveScript = new DefaultRedisScript<>(ARCHIVE_LUA, Long.class);


    // ==========================================
    // 2. 核心功能 (供外部调用)
    // ==========================================

    /**
     * 功能一：去拿历史聊天记录 (婴儿级解释：帮我回忆一下)
     *
     * @param key 比如 "chat:history:user_001"
     * @return 这是一个“未来凭证”(CompletableFuture)，意思是“我派人去拿了，拿到后通知你”
     */
    public CompletableFuture<List<String>> getListAsync(String key) {

        // 告诉 Caffeine：帮我找找这个 key 的记忆
        return asyncCache.get(key, (k, executor) ->

                // 如果 Caffeine 忘了，它就会执行下面这段代码，派跑腿小弟去 Redis (大笔记本) 里抄回来
                CompletableFuture.supplyAsync(() -> {
                    System.out.println(">>> [跑腿小弟] 脑子里没记住，正在去 Redis 翻找: " + k);

                    // 去 Redis 把这个人的所有聊天记录拿出来 (0 到 -1 代表从头拿到尾)
                    List<String> history = redisTemplate.opsForList().range(k, 0, -1);

                    // 如果 Redis 里也没有 (新用户)，就给他一个空列表 []，千万不能给 null (会报错的！)
                    if (history != null && !history.isEmpty()) {
                        return history;
                    } else {
                        return new ArrayList<>();
                    }
                }, executor) // 让 Caffeine 专属的跑腿小弟去干活
        );
    }

    /**
     * 功能二：打包存入聊天记录 (婴儿级解释：把刚才聊的写进日记本)
     *
     * @param key      比如 "chat:history:user_001"
     * @param userMsg  用户说的话，比如 "User: 你好"
     * @param aiMsg    AI 说的话，比如 "AI: 我是 Nexus"
     * @return 这是一个“未来凭证”，代表归档动作正在后台默默进行
     */
    public CompletableFuture<Void> archiveConversationAsync(String key, String userMsg, String aiMsg) {

        // 派跑腿小弟去干活 (runAsync 就是只干活，不带东西回来)
        return CompletableFuture.runAsync(() -> {

                    // 1. 念魔法咒语！让 Redis 一瞬间干完存入和裁剪的活 (ARGV[3] 的 "20" 代表只留最近20条)
                    redisTemplate.execute(archiveScript, Collections.singletonList(key), userMsg, aiMsg, "20");

                    // 2. 暴力洗脑！因为大笔记本 (Redis) 更新了，必须把大脑内存 (Caffeine) 里旧的记忆抹掉
                    asyncCache.synchronous().invalidate(key);

                    System.out.println(">>> [跑腿小弟] 对话已存入 Redis，并成功清除了旧的脑内记忆: " + key);

                }, asyncExecutor)

                // 兜底机制：万一跑腿小弟路上摔跤了 (报错了)，就在这里擦屁股
                .exceptionally(ex -> {
                    System.err.println("[致命错误] 存入聊天记录失败，原因: " + ex.getMessage());
                    return null; // 擦干血迹，假装无事发生
                });
    }
    // ==========================================
    // 3. RAG 缓存专用 (K-V 结构)
    // 作用：把大模型写对的 SQL 存起来。
    // ==========================================

    /**
     * 读取 SQL 缓存
     * @param key 比如 "cache:sql:md5值"
     * @return 缓存的 SQL 语句，如果没有则返回 null
     */
    public String getCache(String key) {
        // 【理由说明 1】：为什么这里是同步的 (没用 CompletableFuture)？
        // 因为 SqlGenNode 里，我们需要立刻知道有没有缓存，才能决定下一步是去查数据库还是直接执行。
        // 如果这里用异步，流程控制会变得极其复杂（需要把整个执行链包装成回调）。
        // 考虑到单纯的 Redis GET 操作极快（通常 < 1ms），为了代码的简洁性，这里使用同步读取。
        return redisTemplate.opsForValue().get(key);
    }

    /**
     * 写入 SQL 缓存
     * @param key   比如 "cache:sql:md5值"
     * @param value 验证成功的 SQL 语句
     */
    public void setCache(String key, String value) {
        // 【理由说明 2】：为什么这里是异步的？
        // 因为在 SqlExecNode 里，SQL 已经执行成功了，马上就要去 ReporterNode 润色结果给用户了。
        // 写缓存这个动作，用户根本不关心。
        // 我们绝对不能让主业务流程停下来等 Redis 写完。
        // 所以，我们派一个跑腿小弟 (asyncExecutor) 在后台默默地去写。这叫 "Fire and Forget" (射后不理)。
        CompletableFuture.runAsync(() -> {

            // opsForValue() 是操作单个字符串的。设置 24 小时过期，防止数据库表结构变了，缓存的 SQL 还是旧的。
            redisTemplate.opsForValue().set(key, value, 24, TimeUnit.HOURS);

            System.out.println(">>> [跑腿小弟] SQL 逻辑已存入武器库: " + key);

        }, asyncExecutor);
    }
}
