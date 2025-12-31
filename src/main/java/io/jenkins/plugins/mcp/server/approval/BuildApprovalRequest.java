package io.jenkins.plugins.mcp.server.approval;

import java.io.Serializable;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Represents a pending build approval request.
 * When a protected job is triggered via MCP, an approval request is created
 * and must be approved by an admin before the build can proceed.
 */
public class BuildApprovalRequest implements Serializable {
    
    private static final long serialVersionUID = 1L;

    public enum Status {
        PENDING,
        APPROVED,
        REJECTED,
        EXPIRED
    }

    private final String id;
    private final String jobFullName;
    private final Map<String, Object> parameters;
    private final String requesterId;
    private final String requesterDisplayName;
    private final Instant createdAt;
    private final Instant expiresAt;
    
    private Status status;
    private String approvedBy;
    private String rejectedBy;
    private String rejectionReason;
    private Instant resolvedAt;
    private Integer triggeredBuildNumber;

    public BuildApprovalRequest(
            String jobFullName,
            Map<String, Object> parameters,
            String requesterId,
            String requesterDisplayName,
            int timeoutMinutes) {
        this.id = UUID.randomUUID().toString();
        this.jobFullName = jobFullName;
        this.parameters = parameters;
        this.requesterId = requesterId;
        this.requesterDisplayName = requesterDisplayName;
        this.createdAt = Instant.now();
        this.expiresAt = createdAt.plusSeconds(timeoutMinutes * 60L);
        this.status = Status.PENDING;
    }

    public String getId() {
        return id;
    }

    public String getJobFullName() {
        return jobFullName;
    }

    public Map<String, Object> getParameters() {
        return parameters;
    }

    public String getRequesterId() {
        return requesterId;
    }

    public String getRequesterDisplayName() {
        return requesterDisplayName;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Status getStatus() {
        return status;
    }

    public String getApprovedBy() {
        return approvedBy;
    }

    public String getRejectedBy() {
        return rejectedBy;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public Integer getTriggeredBuildNumber() {
        return triggeredBuildNumber;
    }

    public boolean isPending() {
        return status == Status.PENDING && !isExpired();
    }

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    /**
     * Mark this request as approved and trigger the build.
     * @param approver The user who approved the request
     */
    public void approve(String approver) {
        if (!isPending()) {
            throw new IllegalStateException("Cannot approve a request that is not pending");
        }
        this.status = Status.APPROVED;
        this.approvedBy = approver;
        this.resolvedAt = Instant.now();
    }

    /**
     * Mark this request as rejected.
     * @param rejecter The user who rejected the request
     * @param reason The reason for rejection
     */
    public void reject(String rejecter, String reason) {
        if (!isPending()) {
            throw new IllegalStateException("Cannot reject a request that is not pending");
        }
        this.status = Status.REJECTED;
        this.rejectedBy = rejecter;
        this.rejectionReason = reason;
        this.resolvedAt = Instant.now();
    }

    /**
     * Mark this request as expired.
     */
    public void expire() {
        if (status == Status.PENDING) {
            this.status = Status.EXPIRED;
            this.resolvedAt = Instant.now();
        }
    }

    /**
     * Set the build number after the build was successfully triggered.
     */
    public void setTriggeredBuildNumber(Integer buildNumber) {
        this.triggeredBuildNumber = buildNumber;
    }

    /**
     * Get the time remaining before expiration in seconds.
     */
    public long getTimeRemainingSeconds() {
        long remaining = expiresAt.getEpochSecond() - Instant.now().getEpochSecond();
        return Math.max(0, remaining);
    }

    @Override
    public String toString() {
        return "BuildApprovalRequest{" +
                "id='" + id + '\'' +
                ", jobFullName='" + jobFullName + '\'' +
                ", requesterId='" + requesterId + '\'' +
                ", status=" + status +
                ", createdAt=" + createdAt +
                ", expiresAt=" + expiresAt +
                '}';
    }
}
