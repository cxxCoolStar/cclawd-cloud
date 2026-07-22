package ai.openagent.bootstrap.channel;

import ai.openagent.bootstrap.persistence.ChannelRepository;
import ai.openagent.framework.identity.RequestContext;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Compact channel overview used by the top-level channels page. */
@RestController
@RequiredArgsConstructor
public class ChannelOverviewController {

    private final ChannelRepository channelRepository;
    private final ChannelRuntimeManager runtimeManager;

    @GetMapping("/api/channels")
    public List<ChannelInfo> list() {
        return channelRepository.listBindingsByUser(RequestContext.requireUserId()).stream()
                .map(binding -> new ChannelInfo(
                        binding.channelType(),
                        binding.displayName(),
                        binding.enabled(),
                        runtimeManager.status(binding.channelType(), binding.accountId())))
                .toList();
    }

    public record ChannelInfo(String type, String botUsername, boolean enabled, String status) {}
}
