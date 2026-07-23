package ai.openagent.bootstrap.channel.service;

import ai.openagent.bootstrap.channel.controller.request.ChannelMessageQueryRequest;
import ai.openagent.bootstrap.channel.controller.vo.ChannelAccountVO;
import ai.openagent.bootstrap.channel.controller.vo.ChannelMessageDetailVO;
import ai.openagent.bootstrap.channel.controller.vo.ChannelMessagePageVO;
import ai.openagent.bootstrap.channel.controller.vo.ChannelRuntimeVO;
import ai.openagent.bootstrap.channel.controller.vo.ChannelSummaryVO;
import java.util.List;

public interface ChannelObservationService {

    List<ChannelAccountVO> listAccounts();

    ChannelSummaryVO summary();

    ChannelMessagePageVO listMessages(ChannelMessageQueryRequest request);

    ChannelMessageDetailVO getMessage(String messageId);

    ChannelMessagePageVO listFailures(ChannelMessageQueryRequest request);

    ChannelRuntimeVO runtime();
}
