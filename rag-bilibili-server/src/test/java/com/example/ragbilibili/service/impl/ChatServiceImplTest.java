package com.example.ragbilibili.service.impl;

import com.example.ragbilibili.entity.Message;
import com.example.ragbilibili.enums.MessageRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ChatServiceImpl.buildMessageHistory() 的单元测试。
 *
 * <p>由于 buildMessageHistory() 是私有方法（private），无法直接调用。
 * 这里使用 Java 反射（Reflection）机制绕过访问限制，直接测试该私有方法的逻辑。
 *
 * <p>反射调用步骤：
 *   1. 通过 Class.getDeclaredMethod() 获取私有方法对象
 *   2. 调用 setAccessible(true) 解除访问限制
 *   3. 通过 Method.invoke(实例, 参数...) 执行方法并获取返回值
 *
 * <p>测试覆盖场景：
 *   - 空消息列表
 *   - 当前用户消息被正确排除（excludeId 过滤）
 *   - 消息角色正确映射为 Spring AI 的 UserMessage / AssistantMessage
 *   - 滑动窗口截取最近 MAX_HISTORY(10) 条
 *   - 消息按 createTime 升序排列
 */
class ChatServiceImplTest {

    /** 被测对象，直接 new 即可，不需要 Spring 容器（无需 @SpringBootTest） */
    private ChatServiceImpl chatService;

    /**
     * 通过反射持有 buildMessageHistory 方法引用，在所有测试中复用。
     * 声明为字段是为了避免每个测试方法重复获取。
     */
    private Method buildMessageHistory;

    /**
     * 每个测试方法执行前初始化：
     * 1. 创建 ChatServiceImpl 实例
     * 2. 获取私有方法 buildMessageHistory 的反射句柄并解除访问限制
     */
    @BeforeEach
    void setUp() throws Exception {
        chatService = new ChatServiceImpl();
        // getDeclaredMethod 可获取私有方法，参数为方法名 + 参数类型列表
        buildMessageHistory = ChatServiceImpl.class.getDeclaredMethod(
                "buildMessageHistory", List.class, Long.class);
        // setAccessible(true) 允许在类外部调用私有方法
        buildMessageHistory.setAccessible(true);
    }

    /**
     * 通过反射调用 buildMessageHistory 的便捷封装，避免每个测试都写 try-catch 和强转。
     *
     * @param messages  传入的消息列表
     * @param excludeId 需要排除的消息 ID（当前用户刚发送的消息）
     * @return Spring AI Message 列表
     */
    @SuppressWarnings("unchecked")
    private List<org.springframework.ai.chat.messages.Message> invoke(
            List<Message> messages, Long excludeId) throws Exception {
        return (List<org.springframework.ai.chat.messages.Message>)
                buildMessageHistory.invoke(chatService, messages, excludeId);
    }

    /**
     * 快速构造测试用 Message 实体的工厂方法，减少测试代码冗余。
     */
    private Message msg(Long id, String role, String content, LocalDateTime time) {
        Message m = new Message();
        m.setId(id);
        m.setRole(role);
        m.setContent(content);
        m.setCreateTime(time);
        return m;
    }

    // =========================================================================
    // 测试用例
    // =========================================================================

    /**
     * 场景：传入空消息列表时，应返回空列表，不抛异常。
     */
    @Test
    void buildMessageHistory_emptyInput_returnsEmptyList() throws Exception {
        List<org.springframework.ai.chat.messages.Message> result = invoke(List.of(), 99L);
        assertThat(result).isEmpty();
    }

    /**
     * 场景：excludeId 指向的消息（当前用户刚发送的消息）应从历史中排除，
     * 避免将尚未得到回答的问题混入上下文历史。
     */
    @Test
    void buildMessageHistory_excludesCurrentUserMessage_byId() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        List<Message> messages = List.of(
                msg(1L, MessageRole.USER.getCode(), "历史问题", now.minusSeconds(10)),
                msg(2L, MessageRole.ASSISTANT.getCode(), "历史回答", now.minusSeconds(5)),
                msg(3L, MessageRole.USER.getCode(), "当前问题", now)  // 应被排除
        );

        List<org.springframework.ai.chat.messages.Message> result = invoke(messages, 3L);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getText()).isEqualTo("历史问题");
        assertThat(result.get(1).getText()).isEqualTo("历史回答");
    }

    /**
     * 场景：消息角色应正确映射为 Spring AI 的消息类型：
     * - USER   → org.springframework.ai.chat.messages.UserMessage
     * - ASSISTANT → org.springframework.ai.chat.messages.AssistantMessage
     * 类型正确是 LLM 能区分对话轮次的前提。
     */
    @Test
    void buildMessageHistory_mapsRoles_toCorrectSpringAiMessageTypes() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        List<Message> messages = List.of(
                msg(1L, MessageRole.USER.getCode(), "用户说", now.minusSeconds(2)),
                msg(2L, MessageRole.ASSISTANT.getCode(), "助手说", now.minusSeconds(1))
        );

        List<org.springframework.ai.chat.messages.Message> result = invoke(messages, 99L);

        assertThat(result).hasSize(2);
        assertThat(result.get(0)).isInstanceOf(UserMessage.class);
        assertThat(result.get(0).getText()).isEqualTo("用户说");
        assertThat(result.get(1)).isInstanceOf(AssistantMessage.class);
        assertThat(result.get(1).getText()).isEqualTo("助手说");
    }

    /**
     * 场景：当历史消息超过 MAX_HISTORY(10) 条时，应只保留最近的 10 条，
     * 即滑动窗口策略——丢弃最早的消息，防止 prompt 过长超出 LLM token 限制。
     */
    @Test
    void buildMessageHistory_slidingWindow_keepsLatest10Messages() throws Exception {
        LocalDateTime base = LocalDateTime.now().minusSeconds(20);
        List<Message> messages = new ArrayList<>();
        // 构造 15 条消息，createTime 依次递增
        for (int i = 1; i <= 15; i++) {
            messages.add(msg((long) i, MessageRole.USER.getCode(),
                    "消息" + i, base.plusSeconds(i)));
        }

        List<org.springframework.ai.chat.messages.Message> result = invoke(messages, 99L);

        // 只保留最近 10 条（第 6-15 条）
        assertThat(result).hasSize(10);
        assertThat(result.get(0).getText()).isEqualTo("消息6");
        assertThat(result.get(9).getText()).isEqualTo("消息15");
    }

    /**
     * 场景：无论调用方传入的 List 顺序如何，返回结果都应按 createTime 升序排列，
     * 确保 LLM 收到的对话历史时序正确。
     */
    @Test
    void buildMessageHistory_sortsMessages_byCreateTimeAscending() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        // 故意乱序传入
        List<Message> messages = List.of(
                msg(3L, MessageRole.ASSISTANT.getCode(), "第三条", now),
                msg(1L, MessageRole.USER.getCode(), "第一条", now.minusSeconds(2)),
                msg(2L, MessageRole.USER.getCode(), "第二条", now.minusSeconds(1))
        );

        List<org.springframework.ai.chat.messages.Message> result = invoke(messages, 99L);

        assertThat(result).hasSize(3);
        assertThat(result.get(0).getText()).isEqualTo("第一条");
        assertThat(result.get(1).getText()).isEqualTo("第二条");
        assertThat(result.get(2).getText()).isEqualTo("第三条");
    }
}
