package ai.openagent.bootstrap.tool.websearch;

import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * 搜索 provider 回退链（对照 fastclaw toolproviders.Chain.Execute）
 *
 * <p>
 * 按 Order 顺序尝试：未配置的 provider 跳过；retriable 失败（网络、
 * 超时、429、5xx、无结果）回退下一个；非 retriable 失败立即终止——
 * 配置 bug 快速暴露不被回退掩盖
 * </p>
 */
@Slf4j
public class WebSearchChain {

    private final List<WebSearchProvider> providers;

    public WebSearchChain(List<WebSearchProvider> providers) {
        this.providers = providers;
    }

    /**
     * 链是否可用（fastclaw Chain.Available：至少一个 provider 已配置；
     * 不可用时工具侧应隐藏/拒绝，模型看不到一个用不了的工具）
     */
    public boolean available() {
        return providers.stream().anyMatch(WebSearchProvider::configured);
    }

    /**
     * 按顺序执行，返回首个成功的归一化结果
     *
     * @throws WebSearchException 链穷尽（以最后一个失败为准）；首个非
     *                            retriable 失败立即抛出
     */
    public List<WebSearchProvider.ResultItem> search(String query, int count) {
        WebSearchException lastError = null;
        for (WebSearchProvider provider : providers) {
            if (!provider.configured()) {
                continue;
            }
            try {
                List<WebSearchProvider.ResultItem> items = provider.search(query, count);
                if (items.isEmpty()) {
                    throw WebSearchException.NO_RESULTS;
                }
                return items;
            } catch (WebSearchException error) {
                lastError = error;
                if (!error.retriable()) {
                    throw error;
                }
                log.warn("[websearch] provider {} 失败，回退下一个：{}", provider.name(), error.getMessage());
            }
        }
        throw lastError == null
                ? new WebSearchException("no web search provider configured", false)
                : lastError;
    }

    /**
     * 按 Order 组装链（未知 provider 名跳过并告警）
     */
    public static WebSearchChain of(String order, List<WebSearchProvider> available) {
        List<WebSearchProvider> chain = new ArrayList<>();
        for (String ref : order.split(",")) {
            String name = ref.trim();
            if (name.isEmpty()) {
                continue;
            }
            available.stream()
                    .filter(provider -> provider.name().equals(name))
                    .findFirst()
                    .ifPresentOrElse(chain::add,
                            () -> log.warn("[websearch] 链引用了未知 provider：{}", name));
        }
        return new WebSearchChain(chain);
    }
}
