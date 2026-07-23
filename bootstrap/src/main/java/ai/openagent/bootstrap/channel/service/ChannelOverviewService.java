package ai.openagent.bootstrap.channel.service;

import ai.openagent.bootstrap.channel.controller.vo.ChannelOverviewVO;
import java.util.List;

public interface ChannelOverviewService {

    List<ChannelOverviewVO> list();
}