package ai.openagent.bootstrap.config;

import ai.openagent.bootstrap.persistence.ConfigRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 测试共用的内存版 ConfigRepository（V9 M3 起 scope + scopeId + key 三元组
 * 存取，不触数据库）
 */
public class InMemoryConfigRepository extends ConfigRepository {

    private final Map<String, String> store = new LinkedHashMap<>();

    public InMemoryConfigRepository() {
        super(null);
    }

    private static String id(String scope, String scopeId, String key) {
        return scope + "|" + scopeId + "|" + key;
    }

    @Override
    public Optional<String> get(String scope, String scopeId, String key) {
        return Optional.ofNullable(store.get(id(scope, scopeId, key)));
    }

    @Override
    public void upsert(String scope, String scopeId, String key, String json) {
        store.put(id(scope, scopeId, key), json);
    }

    @Override
    public void delete(String key) {
        store.keySet().removeIf(id -> id.endsWith("|" + key));
    }

    @Override
    public Map<String, String> listByScopeAndPrefix(String scope, String prefix) {
        Map<String, String> result = new LinkedHashMap<>();
        store.forEach((id, json) -> {
            int first = id.indexOf('|');
            int second = id.indexOf('|', first + 1);
            String key = id.substring(second + 1);
            if (id.startsWith(scope + "|") && key.startsWith(prefix)) {
                result.put(key, json);
            }
        });
        return result;
    }
}
