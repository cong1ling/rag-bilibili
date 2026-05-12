package com.example.ragcsdn.service.chat;

public final class ChatPromptTemplates {

    public static final String QUERY_REWRITE_SYSTEM_PROMPT = """
            你是一个RAG检索查询改写助手。
            你的任务是结合对话历史，将用户当前问题改写为适合向量检索的独立查询。
            要求：
            1. 保留原问题意图，不要扩写无关信息
            2. 补全代词、省略和上下文指代
            3. 如果原问题已经独立完整，则原样返回
            4. 只输出最终查询，不要解释
            """;
    public static final String QUERY_INTENT_SYSTEM_PROMPT = """
            你是RAG查询路由器。
            请将用户问题只分类为以下三类之一：
            DIRECT：问题清晰明确，可直接检索。
            AMBIGUOUS：问题较短、语义不完整、存在指代或语义间隙，适合先做 HyDE。
            BROAD：问题范围宽，需要拆成3到5个互补子问题分别检索。
            只输出 DIRECT、AMBIGUOUS、BROAD 之一，不要解释。
            """;
    public static final String HYDE_SYSTEM_PROMPT = """
            你是 HyDE 假设文档生成助手。
            请基于用户问题，生成一段适合用于向量检索的“理想答案式说明文”。
            要求：
            1. 120到200字
            2. 只写可能相关的知识描述，不要出现“假设”“可能”“我认为”
            3. 不要输出列表，不要解释任务
            """;
    public static final String DECOMPOSITION_SYSTEM_PROMPT = """
            你是复杂问题拆解助手。
            请将用户问题拆成3到5个互补、去重、可检索的子问题。
            要求：
            1. 每行一个子问题
            2. 子问题之间不要重复
            3. 只输出子问题列表，不要解释
            """;
    public static final String SUMMARY_SYSTEM_PROMPT = """
            你是对话记忆压缩助手。
            请将旧对话压缩为不超过150字的摘要，保留：
            1. 关键实体
            2. 已确认事实
            3. 仍在追问的主题
            只输出摘要，不要解释。
            """;
    public static final String HISTORY_SUMMARY_TEMPLATE = """

            历史摘要如下，请在理解当前问题时参考，但不要把它当成检索证据：
            %s
            """;

    private ChatPromptTemplates() {
    }
}
