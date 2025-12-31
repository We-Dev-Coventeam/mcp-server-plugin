package io.jenkins.plugins.mcp.server.approval;

import hudson.Extension;
import hudson.util.FormValidation;
import jenkins.model.GlobalConfiguration;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest2;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

/**
 * Global configuration for MCP protected jobs.
 * Jobs matching the configured patterns will require explicit admin approval
 * before being triggered via MCP, even if the user has build permissions.
 * 
 * This prevents the "Always Allow" bypass in MCP clients like VS Code.
 */
@Extension
public class ProtectedJobsConfiguration extends GlobalConfiguration {

    private String protectedJobPatterns = "";
    private String notificationWebhookUrl = "";
    private int approvalTimeoutMinutes = 30;
    private boolean enabled = false;

    public ProtectedJobsConfiguration() {
        load();
    }

    public static ProtectedJobsConfiguration get() {
        return GlobalConfiguration.all().get(ProtectedJobsConfiguration.class);
    }

    /**
     * Newline or comma-separated list of regex patterns for protected jobs.
     * Example patterns:
     * - .*-prod-.*
     * - .*maintenance.*
     * - deploy/production/.*
     */
    public String getProtectedJobPatterns() {
        return protectedJobPatterns;
    }

    @DataBoundSetter
    public void setProtectedJobPatterns(String protectedJobPatterns) {
        this.protectedJobPatterns = protectedJobPatterns;
        save();
    }

    /**
     * Webhook URL to notify when a build approval is requested.
     * Can be a Teams incoming webhook, Slack, or custom endpoint.
     * The webhook receives a JSON payload with approval details.
     */
    public String getNotificationWebhookUrl() {
        return notificationWebhookUrl;
    }

    @DataBoundSetter
    public void setNotificationWebhookUrl(String notificationWebhookUrl) {
        this.notificationWebhookUrl = notificationWebhookUrl;
        save();
    }

    /**
     * Timeout in minutes for pending approval requests.
     * After this timeout, the request is automatically rejected.
     */
    public int getApprovalTimeoutMinutes() {
        return approvalTimeoutMinutes;
    }

    @DataBoundSetter
    public void setApprovalTimeoutMinutes(int approvalTimeoutMinutes) {
        this.approvalTimeoutMinutes = approvalTimeoutMinutes;
        save();
    }

    /**
     * Whether the protected jobs feature is enabled.
     */
    public boolean isEnabled() {
        return enabled;
    }

    @DataBoundSetter
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        save();
    }

    /**
     * Check if a job matches any of the protected patterns.
     * @param jobFullName The full name of the job (e.g., "folder/subfolder/job-name")
     * @return true if the job requires approval before triggering
     */
    public boolean isJobProtected(String jobFullName) {
        if (!enabled || protectedJobPatterns == null || protectedJobPatterns.isBlank()) {
            return false;
        }
        
        return getPatternList().stream()
                .anyMatch(pattern -> {
                    try {
                        return Pattern.matches(pattern, jobFullName);
                    } catch (PatternSyntaxException e) {
                        return false;
                    }
                });
    }

    /**
     * Parse the patterns string into a list of individual patterns.
     */
    public List<String> getPatternList() {
        if (protectedJobPatterns == null || protectedJobPatterns.isBlank()) {
            return new ArrayList<>();
        }
        
        return Arrays.stream(protectedJobPatterns.split("[,\n]"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    /**
     * Validate the patterns field.
     */
    public FormValidation doCheckProtectedJobPatterns(@QueryParameter String value) {
        if (value == null || value.isBlank()) {
            return FormValidation.ok();
        }
        
        String[] patterns = value.split("[,\n]");
        List<String> invalidPatterns = new ArrayList<>();
        
        for (String pattern : patterns) {
            String trimmed = pattern.trim();
            if (!trimmed.isEmpty()) {
                try {
                    Pattern.compile(trimmed);
                } catch (PatternSyntaxException e) {
                    invalidPatterns.add(trimmed + " (" + e.getDescription() + ")");
                }
            }
        }
        
        if (!invalidPatterns.isEmpty()) {
            return FormValidation.error("Invalid regex patterns: " + String.join(", ", invalidPatterns));
        }
        
        return FormValidation.ok();
    }

    /**
     * Validate the webhook URL field.
     */
    public FormValidation doCheckNotificationWebhookUrl(@QueryParameter String value) {
        if (value == null || value.isBlank()) {
            return FormValidation.ok("No webhook configured - approvals will only be available via Jenkins UI");
        }
        
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            return FormValidation.error("Webhook URL must start with http:// or https://");
        }
        
        return FormValidation.ok();
    }

    @Override
    public boolean configure(StaplerRequest2 req, JSONObject json) throws FormException {
        req.bindJSON(this, json);
        save();
        return true;
    }

    @Override
    public String getDisplayName() {
        return "MCP Protected Jobs";
    }
}
