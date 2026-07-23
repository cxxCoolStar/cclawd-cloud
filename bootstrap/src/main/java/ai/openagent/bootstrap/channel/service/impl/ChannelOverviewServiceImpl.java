package ai.openagent.bootstrap.channel.service.impl;

import ai.openagent.bootstrap.channel.ChannelRuntimeManager;
import ai.openagent.bootstrap.channel.controller.vo.ChannelOverviewVO;
import ai.openagent.bootstrap.channel.service.ChannelOverviewService;
import ai.openagent.bootstrap.persistence.ChannelRepository;
import ai.openagent.framework.identity.RequestContext;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ChannelOverviewServiceImpl implements ChannelOverviewService {

    private final ChannelRepository channelRepository;
    private final ChannelRuntimeManager runtimeManager;

    @Override
    public List<ChannelOverviewVO> list() {
        return channelRepository.listBindingsByUser(RequestContext.requireUserId()).stream()
                .map(binding -> new ChannelOverviewVO(
                        binding.channelType(),
                        binding.displayName(),
                        binding.enabled(),
                        runtimeManager.status(binding.channelType(), binding.accountId())))
                .toList();
    }
}