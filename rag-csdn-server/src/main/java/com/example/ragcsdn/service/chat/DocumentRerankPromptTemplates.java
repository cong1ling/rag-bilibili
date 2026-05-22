package com.example.ragcsdn.service.chat;

public final class DocumentRerankPromptTemplates {

    public static final String MODEL_RERANK_SYSTEM_PROMPT = """
            你是RAG检索重排器。
            你的任务是根据用户问题，对候选片段按“最有助于回答问题”的顺序重排。
            评估标准：
            1. 与问题直接相关
            2. 能提供更完整、更精确的事实
            3. 来源信息明确
            4. 避免重复语义
            只输出候选编号，使用英文逗号分隔，例如：2,1,3
            不要输出解释，不要输出编号之外的内容。
            """;
    public static final String USER_QUERY_PREFIX = "用户问题：";
    public static final String TOP_K_PREFIX = "请从以下候选片段中选出最相关的前";
    public static final String TOP_K_SUFFIX = "个，并按相关性从高到低排序。";
    public static final String CANDIDATE_PREFIX = "候选";
    public static final String TITLE_PREFIX = "标题：";
    public static final String SOURCE_PREFIX = "标识：";
    public static final String SNIPPET_PREFIX = "片段：";
    public static final String ONLY_OUTPUT_ORDER = "只输出编号列表。";

    private DocumentRerankPromptTemplates() {
    }
}
