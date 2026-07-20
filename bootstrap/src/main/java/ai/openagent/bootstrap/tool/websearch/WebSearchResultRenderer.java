package ai.openagent.bootstrap.tool.websearch;

import java.util.List;

/**
 * 搜索结果渲染器，统一格式化搜索结果输出
 */
public final class WebSearchResultRenderer {

    private WebSearchResultRenderer() {}

    /**
     * 统一渲染：所有 provider 的输出对模型完全一致
     */
    public static String render(String query, List<WebSearchProvider.ResultItem> items) {
        if (items.isEmpty()) {
            return "No results found for: " + query;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Search results for: ").append(query).append("\n\n");
        for (int i = 0; i < items.size(); i++) {
            WebSearchProvider.ResultItem item = items.get(i);
            sb.append(i + 1).append(". ").append(item.title()).append('\n');
            sb.append("   URL: ").append(item.url()).append('\n');
            sb.append("   ").append(cleanSnippet(item.snippet())).append("\n\n");
        }
        return sb.toString();
    }

    /**
     * 剥离搜索引擎漏进 snippet 的 HTML 标签并还原 HTML 实体
     */
    public static String cleanSnippet(String snippet) {
        if (snippet == null) {
            return "";
        }
        String cleaned = snippet.replaceAll("<[^>]+>", "")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&nbsp;", " ");
        return cleaned.replaceAll("[ \\t]+", " ").trim();
    }
}
