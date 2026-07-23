package ai.openagent.bootstrap.channel.controller;

import ai.openagent.bootstrap.channel.controller.request.ChannelMessageQueryRequest;
import ai.openagent.bootstrap.channel.controller.vo.ChannelAccountVO;
import ai.openagent.bootstrap.channel.controller.vo.ChannelMessageDetailVO;
import ai.openagent.bootstrap.channel.controller.vo.ChannelMessagePageVO;
import ai.openagent.bootstrap.channel.controller.vo.ChannelRuntimeVO;
import ai.openagent.bootstrap.channel.controller.vo.ChannelSummaryVO;
import ai.openagent.bootstrap.channel.service.ChannelObservationService;
import ai.openagent.framework.convention.Result;
import ai.openagent.framework.web.Results;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only operations API for the top-level Channel console. */
@RestController
@RequestMapping("/api/channels")
@RequiredArgsConstructor
public class ChannelObservationController {

    private final ChannelObservationService observationService;

    @GetMapping("/accounts")
    public Result<List<ChannelAccountVO>> accounts() {
        return Results.success(observationService.listAccounts());
    }

    @GetMapping("/summary")
    public Result<ChannelSummaryVO> summary() {
        return Results.success(observationService.summary());
    }

    @GetMapping("/messages")
    public Result<ChannelMessagePageVO> messages(@ModelAttribute ChannelMessageQueryRequest request) {
        return Results.success(observationService.listMessages(request));
    }

    @GetMapping("/messages/{messageId}")
    public Result<ChannelMessageDetailVO> message(@PathVariable String messageId) {
        return Results.success(observationService.getMessage(messageId));
    }

    @GetMapping("/failures")
    public Result<ChannelMessagePageVO> failures(@ModelAttribute ChannelMessageQueryRequest request) {
        return Results.success(observationService.listFailures(request));
    }

    @GetMapping("/runtime")
    public Result<ChannelRuntimeVO> runtime() {
        return Results.success(observationService.runtime());
    }
}
