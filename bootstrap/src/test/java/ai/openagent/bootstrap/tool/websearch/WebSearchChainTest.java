package ai.openagent.bootstrap.tool.websearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.openagent.bootstrap.tool.websearch.WebSearchProvider.ResultItem;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 回退链与渲染单测（对照 fastclaw toolproviders.Chain 语义）
 */
class WebSearchChainTest {

    private static ResultItem item(String title) {
        return new ResultItem(title, "https://x/" + title, "snippet");
    }

    private static WebSearchProvider provider(String name, boolean configured,
                                              List<ResultItem> result, WebSearchException error) {
        return new WebSearchProvider() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public boolean configured() {
                return configured;
            }

            @Override
            public List<ResultItem> search(String query, int count) {
                if (error != null) {
                    throw error;
                }
                return result;
            }
        };
    }

    @Test
    void fallsBackOnRetriableFailure() {
        WebSearchChain chain = new WebSearchChain(List.of(
                provider("a", true, null, new WebSearchException("timeout", true)),
                provider("b", true, List.of(item("t1")), null)));
        assertEquals(1, chain.search("q", 5).size());
    }

    @Test
    void stopsOnNonRetriableFailure() {
        WebSearchChain chain = new WebSearchChain(List.of(
                provider("a", true, null, new WebSearchException("bad config", false)),
                provider("b", true, List.of(item("t1")), null)));
        assertThrows(WebSearchException.class, () -> chain.search("q", 5));
    }

    @Test
    void skipsUnconfiguredProviders() {
        WebSearchChain chain = new WebSearchChain(List.of(
                provider("a", false, List.of(item("hidden")), null),
                provider("b", true, List.of(item("t1")), null)));
        assertEquals("t1", chain.search("q", 5).get(0).title());
    }

    @Test
    void emptyResultIsRetriable() {
        WebSearchChain chain = new WebSearchChain(List.of(
                provider("a", true, List.of(), null),
                provider("b", true, List.of(item("t1")), null)));
        assertEquals(1, chain.search("q", 5).size());
    }

    @Test
    void availabilityRequiresConfiguredProvider() {
        assertFalse(new WebSearchChain(List.of(provider("a", false, null, null))).available());
        assertTrue(new WebSearchChain(List.of(provider("a", true, null, null))).available());
    }

    @Test
    void rendersAlignedFormat() {
        String text = WebSearchResultRenderer.render("openai", List.of(
                new ResultItem("OpenAI", "https://openai.com", "<strong>AGI</strong> &amp; safety")));
        assertTrue(text.startsWith("Search results for: openai\n"));
        assertTrue(text.contains("1. OpenAI\n   URL: https://openai.com\n   AGI & safety"));
        assertEquals("No results found for: q", WebSearchResultRenderer.render("q", List.of()));
    }

    @Test
    void cleanSnippetStripsTagsAndEntities() {
        assertEquals("AGI & \"safety\" <ok>",
                WebSearchResultRenderer.cleanSnippet("<b>AGI</b> &amp; &quot;safety&quot; &lt;ok&gt;"));
    }
}
