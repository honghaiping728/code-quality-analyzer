package com.cq.agent;

/**
 * 大模型客户端
 * <p>
 * 抽象出接口是为了让整条链路在没有 API Key 的环境下依然可跑：真实实现走
 * OpenAI 兼容协议，离线实现给出确定性的兜底结果，二者对上层完全可替换。
 */
public interface LlmClient {

    /** 客户端名称，用于日志与状态展示 */
    String name();

    /** 当前是否可用（例如是否配置了 API Key、网络是否可达） */
    boolean available();

    /**
     * 是否为离线兜底实现
     * <p>
     * 用于判断结果来源（如修复建议标注 LLM / TEMPLATE）。之所以做成接口方法而不是让调用方
     * {@code instanceof}：客户端可能被装饰（运行时切换配置），类型判断会失真。
     */
    boolean offline();

    /**
     * 发起一次对话补全
     * @param systemPrompt 系统提示词
     * @param userPrompt 用户提示词
     * @return 补全结果
     */
    LlmResponse complete(String systemPrompt, String userPrompt);
}
