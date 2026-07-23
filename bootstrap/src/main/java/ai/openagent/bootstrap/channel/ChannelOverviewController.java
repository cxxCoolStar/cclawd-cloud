package ai.openagent.bootstrap.channel;

import ai.openagent.bootstrap.channel.controller.vo.ChannelOverviewVO;
import ai.openagent.bootstrap.channel.service.ChannelOverviewService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ChannelOverviewController {

    private final ChannelOverviewService channelOverviewService;

    @GetMapping("/api/channels")
    public List<ChannelOverviewVO> list() {
        return channelOverviewService.list();
    }
}