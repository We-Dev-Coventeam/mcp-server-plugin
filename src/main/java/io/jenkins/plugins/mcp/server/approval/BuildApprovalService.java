package io.jenkins.plugins.mcp.server.approval;

import hudson.Extension;
import hudson.model.Job;
import hudson.model.ParameterDefinition;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.Queue;
import hudson.model.User;
import hudson.security.ACL;
import io.jenkins.plugins.mcp.server.extensions.util.ParameterValueFactory;
import jenkins.model.Jenkins;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.Nullable;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Service for managing build approval requests.
 * This is a singleton that stores pending approvals in memory.
 * Approvals are lost on Jenkins restart (by design - pending approvals should expire).
 */
@Extension
public class BuildApprovalService {

    private static final Logger log = LoggerFactory.getLogger(BuildApprovalService.class);
    
    private final ConcurrentHashMap<String, BuildApprovalRequest> pendingApprovals = new ConcurrentHashMap<>();
    private final HttpClient httpClient;

    public BuildApprovalService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public static BuildApprovalService get() {
        return Jenkins.get().getExtensionList(BuildApprovalService.class).get(0);
    }

    /**
     * Check if a job requires approval before triggering.
     */
    public boolean requiresApproval(String jobFullName) {
        ProtectedJobsConfiguration config = ProtectedJobsConfiguration.get();
        return config != null && config.isJobProtected(jobFullName);
    }

    /**
     * Create a new approval request for a protected job.
     * @return The created approval request
     */
    public BuildApprovalRequest createApprovalRequest(
            String jobFullName,
            Map<String, Object> parameters,
            @Nullable User requester) {
        
        ProtectedJobsConfiguration config = ProtectedJobsConfiguration.get();
        int timeout = config != null ? config.getApprovalTimeoutMinutes() : 30;
        
        String requesterId = requester != null ? requester.getId() : "anonymous";
        String requesterDisplayName = requester != null ? requester.getDisplayName() : "Anonymous (MCP)";
        
        BuildApprovalRequest request = new BuildApprovalRequest(
                jobFullName,
                parameters,
                requesterId,
                requesterDisplayName,
                timeout
        );
        
        pendingApprovals.put(request.getId(), request);
        
        log.info("Created approval request {} for job {} by {}", 
                request.getId(), jobFullName, requesterId);
        
        // Send webhook notification asynchronously
        sendWebhookNotification(request);
        
        return request;
    }

    /**
     * Get a pending approval request by ID.
     */
    @Nullable
    public BuildApprovalRequest getApprovalRequest(String id) {
        BuildApprovalRequest request = pendingApprovals.get(id);
        if (request != null && request.isExpired() && request.getStatus() == BuildApprovalRequest.Status.PENDING) {
            request.expire();
        }
        return request;
    }

    /**
     * Get all pending approval requests.
     */
    public List<BuildApprovalRequest> getPendingApprovals() {
        // Clean up expired requests
        pendingApprovals.values().stream()
                .filter(r -> r.isExpired() && r.getStatus() == BuildApprovalRequest.Status.PENDING)
                .forEach(BuildApprovalRequest::expire);
        
        return pendingApprovals.values().stream()
                .filter(BuildApprovalRequest::isPending)
                .collect(Collectors.toList());
    }

    /**
     * Approve a pending request and trigger the build.
     * @param id The approval request ID
     * @param approver The user approving the request
     * @return The build number if successful, null otherwise
     */
    @Nullable
    public Integer approveAndTrigger(String id, User approver) {
        BuildApprovalRequest request = getApprovalRequest(id);
        if (request == null) {
            log.warn("Approval request {} not found", id);
            return null;
        }
        
        if (!request.isPending()) {
            log.warn("Approval request {} is not pending (status: {})", id, request.getStatus());
            return null;
        }
        
        String approverId = approver != null ? approver.getId() : "system";
        request.approve(approverId);
        
        log.info("Approval request {} approved by {}", id, approverId);
        
        // Trigger the build
        Integer buildNumber = triggerBuildInternal(request);
        request.setTriggeredBuildNumber(buildNumber);
        
        return buildNumber;
    }

    /**
     * Reject a pending request.
     * @param id The approval request ID
     * @param rejecter The user rejecting the request
     * @param reason The reason for rejection
     */
    public boolean reject(String id, User rejecter, String reason) {
        BuildApprovalRequest request = getApprovalRequest(id);
        if (request == null) {
            log.warn("Approval request {} not found", id);
            return false;
        }
        
        if (!request.isPending()) {
            log.warn("Approval request {} is not pending (status: {})", id, request.getStatus());
            return false;
        }
        
        String rejecterId = rejecter != null ? rejecter.getId() : "system";
        request.reject(rejecterId, reason);
        
        log.info("Approval request {} rejected by {}: {}", id, rejecterId, reason);
        
        return true;
    }

    /**
     * Trigger the build after approval.
     */
    @Nullable
    private Integer triggerBuildInternal(BuildApprovalRequest request) {
        try {
            Jenkins jenkins = Jenkins.get();
            Job<?, ?> job = jenkins.getItemByFullName(request.getJobFullName(), Job.class);
            
            if (job == null) {
                log.error("Job {} not found", request.getJobFullName());
                return null;
            }
            
            List<ParameterValue> parameterValues = new ArrayList<>();
            ParametersDefinitionProperty paramsDef = job.getProperty(ParametersDefinitionProperty.class);
            
            if (paramsDef != null && request.getParameters() != null && !request.getParameters().isEmpty()) {
                for (ParameterDefinition param : paramsDef.getParameterDefinitions()) {
                    Object value = request.getParameters().get(param.getName());
                    if (value != null) {
                        ParameterValue pv = ParameterValueFactory.createParameterValue(param, value);
                        if (pv != null) {
                            parameterValues.add(pv);
                        }
                    } else {
                        // Use default value
                        ParameterValue defaultValue = param.getDefaultParameterValue();
                        if (defaultValue != null) {
                            parameterValues.add(defaultValue);
                        }
                    }
                }
            }
            
            Queue.Item queueItem;
            if (parameterValues.isEmpty()) {
                queueItem = Queue.getInstance().schedule2(
                        (Queue.Task) job, 
                        0
                ).getItem();
            } else {
                queueItem = Queue.getInstance().schedule2(
                        (Queue.Task) job,
                        0,
                        new ParametersAction(parameterValues)
                ).getItem();
            }
            
            if (queueItem != null) {
                log.info("Build queued for {} (queue item: {})", 
                        request.getJobFullName(), queueItem.getId());
                return (int) queueItem.getId();
            }
            
            return null;
        } catch (Exception e) {
            log.error("Failed to trigger build for {}", request.getJobFullName(), e);
            return null;
        }
    }

    /**
     * Send a webhook notification for a new approval request.
     */
    private void sendWebhookNotification(BuildApprovalRequest request) {
        ProtectedJobsConfiguration config = ProtectedJobsConfiguration.get();
        if (config == null || config.getNotificationWebhookUrl() == null 
                || config.getNotificationWebhookUrl().isBlank()) {
            return;
        }
        
        String webhookUrl = config.getNotificationWebhookUrl();
        String jenkinsUrl = Jenkins.get().getRootUrl();
        if (jenkinsUrl == null) {
            jenkinsUrl = "http://localhost:8080/";
        }
        
        String approvalUrl = jenkinsUrl + "mcp/approval/" + request.getId();
        
        // Build Teams Adaptive Card payload
        String payload = buildTeamsPayload(request, approvalUrl, jenkinsUrl);
        
        // Send async using ExecutorService (compatible with Java 11)
        Executors.newSingleThreadExecutor().submit(() -> {
            try {
                HttpRequest httpRequest = HttpRequest.newBuilder()
                        .uri(URI.create(webhookUrl))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(payload))
                        .timeout(Duration.ofSeconds(30))
                        .build();
                
                HttpResponse<String> response = httpClient.send(httpRequest, 
                        HttpResponse.BodyHandlers.ofString());
                
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    log.info("Webhook notification sent for approval {}", request.getId());
                } else {
                    log.warn("Webhook notification failed for approval {}: {} {}", 
                            request.getId(), response.statusCode(), response.body());
                }
            } catch (IOException | InterruptedException e) {
                log.error("Failed to send webhook notification for approval {}", 
                        request.getId(), e);
            }
        });
    }

    /**
     * Build Microsoft Teams Adaptive Card payload.
     */
    private String buildTeamsPayload(BuildApprovalRequest request, String approvalUrl, String jenkinsUrl) {
        String paramsDisplay = "";
        if (request.getParameters() != null && !request.getParameters().isEmpty()) {
            paramsDisplay = request.getParameters().entrySet().stream()
                    .map(e -> "- **" + e.getKey() + "**: " + e.getValue())
                    .collect(Collectors.joining("\\n"));
        } else {
            paramsDisplay = "_No parameters_";
        }
        
        return """
            {
                "type": "message",
                "attachments": [
                    {
                        "contentType": "application/vnd.microsoft.card.adaptive",
                        "content": {
                            "$schema": "http://adaptivecards.io/schemas/adaptive-card.json",
                            "type": "AdaptiveCard",
                            "version": "1.4",
                            "msteams": {
                                "width": "Full"
                            },
                            "body": [
                                {
                                    "type": "TextBlock",
                                    "size": "Large",
                                    "weight": "Bolder",
                                    "text": "🔒 MCP Build Approval Required",
                                    "wrap": true,
                                    "style": "heading"
                                },
                                {
                                    "type": "FactSet",
                                    "facts": [
                                        {
                                            "title": "Job",
                                            "value": "%s"
                                        },
                                        {
                                            "title": "Requested by",
                                            "value": "%s"
                                        },
                                        {
                                            "title": "Request ID",
                                            "value": "%s"
                                        },
                                        {
                                            "title": "Expires in",
                                            "value": "%d minutes"
                                        }
                                    ]
                                },
                                {
                                    "type": "TextBlock",
                                    "text": "**Parameters:**",
                                    "wrap": true
                                },
                                {
                                    "type": "TextBlock",
                                    "text": "%s",
                                    "wrap": true,
                                    "isSubtle": true
                                }
                            ],
                            "actions": [
                                {
                                    "type": "Action.OpenUrl",
                                    "title": "✅ Review & Approve",
                                    "url": "%s",
                                    "style": "positive"
                                },
                                {
                                    "type": "Action.OpenUrl",
                                    "title": "View Job",
                                    "url": "%sjob/%s"
                                }
                            ]
                        }
                    }
                ]
            }
            """.formatted(
                request.getJobFullName(),
                request.getRequesterDisplayName(),
                request.getId(),
                request.getTimeRemainingSeconds() / 60,
                paramsDisplay,
                approvalUrl,
                jenkinsUrl,
                request.getJobFullName().replace("/", "/job/")
        );
    }

    /**
     * Clean up old resolved requests (keep for 24 hours for audit).
     */
    public void cleanupOldRequests() {
        long oneDayAgo = System.currentTimeMillis() - (24 * 60 * 60 * 1000);
        
        pendingApprovals.entrySet().removeIf(entry -> {
            BuildApprovalRequest request = entry.getValue();
            return request.getResolvedAt() != null 
                    && request.getResolvedAt().toEpochMilli() < oneDayAgo;
        });
    }
}
