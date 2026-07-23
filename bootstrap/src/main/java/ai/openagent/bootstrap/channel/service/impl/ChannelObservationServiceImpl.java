package ai.openagent.bootstrap.channel.service.impl;

import ai.openagent.bootstrap.channel.ChannelClusterStatus;
import ai.openagent.bootstrap.channel.ChannelLeaseService;
import ai.openagent.bootstrap.channel.ChannelRuntimeRegistry;
import ai.openagent.bootstrap.channel.ChannelRuntimeSnapshot;
import ai.openagent.bootstrap.channel.config.ChannelProperties;
import ai.openagent.bootstrap.channel.controller.request.ChannelMessageQueryRequest;
import ai.openagent.bootstrap.channel.controller.vo.ChannelAccountVO;
import ai.openagent.bootstrap.channel.controller.vo.ChannelMessageDetailVO;
import ai.openagent.bootstrap.channel.controller.vo.ChannelMessagePageVO;
import ai.openagent.bootstrap.channel.controller.vo.ChannelMessageVO;
import ai.openagent.bootstrap.channel.controller.vo.ChannelRuntimeVO;
import ai.openagent.bootstrap.channel.controller.vo.ChannelSummaryVO;
import ai.openagent.bootstrap.channel.controller.vo.ChannelTraceStepVO;
import ai.openagent.bootstrap.channel.dao.bo.ChannelAccountRowBO;
import ai.openagent.bootstrap.channel.dao.bo.ChannelMessageRowBO;
import ai.openagent.bootstrap.channel.dao.bo.ChannelSummaryRowBO;
import ai.openagent.bootstrap.channel.dao.mapper.ChannelObservationMapper;
import ai.openagent.bootstrap.identity.service.impl.UserAdminServiceImpl;
import ai.openagent.framework.errorcode.BaseErrorCode;
import ai.openagent.framework.exception.ClientException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ChannelObservationServiceImpl implements ai.openagent.bootstrap.channel.service.ChannelObservationService {

    private final ChannelObservationMapper mapper;
    private final ChannelRuntimeRegistry runtimeRegistry;
    private final ChannelLeaseService leaseService;
    private final ChannelProperties properties;

    @Override
    public List<ChannelAccountVO> listAccounts() {
        UserAdminServiceImpl.requireSuperAdmin();
        return mapper.selectAccounts(userId()).stream().map(this::account).toList();
    }

    @Override
    public ChannelSummaryVO summary() {
        UserAdminServiceImpl.requireSuperAdmin();
        ChannelSummaryRowBO row = mapper.selectSummary(userId(), LocalDate.now(ZoneOffset.UTC)
                .atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli());
        return new ChannelSummaryVO(value(row.getAccountCount()), value(row.getMessagesToday()),
                value(row.getInboxBacklog()), value(row.getOutboxBacklog()), value(row.getInterruptedCount()),
                value(row.getDeadCount()));
    }

    @Override
    public ChannelMessagePageVO listMessages(ChannelMessageQueryRequest request) {
        UserAdminServiceImpl.requireSuperAdmin();
        return page(request, null);
    }

    @Override
    public ChannelMessageDetailVO getMessage(String messageId) {
        UserAdminServiceImpl.requireSuperAdmin();
        ChannelMessageRowBO row = mapper.selectMessage(userId(), messageId);
        if (row == null) {
            throw new ClientException("channel message not found", BaseErrorCode.RESOURCE_NOT_FOUND);
        }
        ChannelMessageVO message = message(row);
        List<ChannelTraceStepVO> timeline = new ArrayList<>();
        timeline.add(step("INBOX", row.getInboxStatus(), row.getCreatedAt(), row.getCreatedAt(), "Gateway persisted message"));
        timeline.add(step("AGENT_RUN", row.getRunId() == null ? "PENDING" : "ATTACHED", row.getCreatedAt(), row.getUpdatedAt(), "Agent run " + value(row.getRunId())));
        timeline.add(step("OUTBOX", value(row.getOutboxStatus(), "NOT_CREATED"), row.getUpdatedAt(), row.getSentAt(), "Outbound delivery record"));
        return new ChannelMessageDetailVO(message, timeline);
    }

    @Override
    public ChannelMessagePageVO listFailures(ChannelMessageQueryRequest request) {
        UserAdminServiceImpl.requireSuperAdmin();
        return page(request, "FAILURES");
    }

    @Override
    public ChannelRuntimeVO runtime() {
        UserAdminServiceImpl.requireSuperAdmin();
        List<ChannelAccountVO> accounts = listAccounts();
        ChannelSummaryVO summary = summary();
        int active = (int) accounts.stream()
                .filter(account -> ChannelClusterStatus.isActive(account.clusterStatus()))
                .count();
        String status = runtimeStatus(accounts);
        return new ChannelRuntimeVO(properties.bus(), properties.roles().stream().sorted().toList(), accounts.size(),
                active, summary.inboxBacklog(), summary.outboxBacklog(), status);
    }

    private ChannelMessagePageVO page(ChannelMessageQueryRequest request, String mode) {
        int page = request == null ? 1 : request.safePage();
        int pageSize = request == null ? 20 : request.safePageSize();
        String channelType = request == null ? null : request.getChannelType();
        String accountId = request == null ? null : request.getAccountId();
        String keyword = request == null ? null : request.getKeyword();
        String status = mode == null ? request == null ? null : request.getStatus() : null;
        List<ChannelMessageRowBO> rows;
        long total;
        status = "FAILURES".equals(mode) ? "FAILURES" : status;
        rows = mapper.selectMessages(userId(), channelType, accountId, status, keyword, pageSize, (page - 1) * pageSize);
        total = mapper.countMessages(userId(), channelType, accountId, status, keyword);
        return new ChannelMessagePageVO(rows.stream().map(this::message).toList(), total, page, pageSize, (long) page * pageSize < total);
    }

    private ChannelAccountVO account(ChannelAccountRowBO row) {
        boolean enabled = Boolean.TRUE.equals(row.getEnabled());
        boolean leaseActive = leaseService.isActive(row.getId());
        Optional<ChannelRuntimeSnapshot> runtime = runtimeRegistry.find(row.getId());
        ChannelRuntimeSnapshot snapshot = runtime.orElse(null);
        String status = ChannelClusterStatus.resolve(enabled, leaseActive, runtime);
        return new ChannelAccountVO(row.getId(), row.getAgentId(), row.getChannelType(), row.getAccountId(), row.getDisplayName(),
                enabled, Boolean.TRUE.equals(row.getSharedIdentity()), status,
                snapshot == null ? "" : snapshot.adapterStatus(), snapshot == null ? "" : snapshot.ownerId(),
                snapshot == null ? null : iso(snapshot.heartbeatAt()),
                snapshot == null ? null : iso(snapshot.lastMessageAt()), snapshot == null ? "" : snapshot.lastError(),
                leaseActive, value(row.getInboxBacklog()), value(row.getOutboxBacklog()), iso(row.getUpdatedAt()));
    }

    private String runtimeStatus(List<ChannelAccountVO> accounts) {
        if (accounts.stream().anyMatch(account -> ChannelClusterStatus.ERROR.equals(account.clusterStatus()))) {
            return ChannelClusterStatus.ERROR;
        }
        if (accounts.stream().anyMatch(account -> ChannelClusterStatus.DEGRADED.equals(account.clusterStatus())
                || ChannelClusterStatus.UNAVAILABLE.equals(account.clusterStatus()))) {
            return ChannelClusterStatus.DEGRADED;
        }
        return ChannelClusterStatus.HEALTHY;
    }

    private ChannelMessageVO message(ChannelMessageRowBO row) {
        return new ChannelMessageVO(row.getId(), row.getChannelType(), row.getAccountId(), row.getDisplayName(), row.getSenderId(),
                row.getText(), row.getInboxStatus(), row.getOutboxStatus(), row.getRunId(), value(row.getAttempts()), row.getLastError(),
                iso(row.getCreatedAt()), iso(row.getUpdatedAt()), iso(row.getSentAt()));
    }

    private ChannelTraceStepVO step(String stage, String status, Long started, Long completed, String detail) {
        return new ChannelTraceStepVO(stage, status, detail, iso(started), iso(completed));
    }

    private String userId() {
        return ai.openagent.framework.identity.RequestContext.requireUserId();
    }

    private static long value(Long value) { return value == null ? 0L : value; }

    private static int value(Integer value) { return value == null ? 0 : value; }

    private static String value(String value) { return value == null ? "" : value; }

    private static String value(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }

    private static String iso(Long epochMillis) { return epochMillis == null ? null : Instant.ofEpochMilli(epochMillis).toString(); }
}
