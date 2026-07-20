package ai.openagent.bootstrap.tool.websearch;

import java.util.List;

/**
 * 网页搜索 provider 接口
 *
 * <p>
 * 每个 provider 是一个无状态后端：配置由属性注入、实例可被所有请求共享。
 * 一个 {@code web_search} 工具背后挂一条 provider 回退链，
 * 模型永远看不到具体 provider
 * </p>
 */
public interface WebSearchProvider {

    /**
     * provider 名（链 Order 中的引用名）
     */
    String name();

    /**
     * 是否已配置可用凭证/端点
     * 返回 false 时表示该 provider 缺少必要配置，在回退链中会被跳过
     */
    boolean configured();

    /**
     * 执行搜索，返回归一化结果（title/url/snippet）
     *
     * @throws WebSearchException 失败；retriable=true 时链回退到下一个 provider
     */
    List<ResultItem> search(String query, int count);

    /**
     * 归一化搜索结果项（所有 provider 统一形状，渲染与后端无关）
     */
    record ResultItem(String title, String url, String snippet) {}
}
