/*
 *
 * The MIT License
 *
 * Copyright (c) 2025, Gong Yi.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 */

package io.jenkins.plugins.mcp.server.extensions;

import static io.jenkins.plugins.mcp.server.extensions.util.JenkinsUtil.getBuildByNumberOrLast;
import static io.jenkins.plugins.mcp.server.extensions.util.ParameterValueFactory.createParameterValue;

import hudson.Extension;
import hudson.model.AbstractItem;
import hudson.model.AdministrativeMonitor;
import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.Computer;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.Job;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.Queue;
import hudson.model.Run;
import hudson.model.User;
import hudson.slaves.Cloud;
import io.jenkins.plugins.mcp.server.McpServerExtension;
import io.jenkins.plugins.mcp.server.annotation.Tool;
import io.jenkins.plugins.mcp.server.annotation.ToolParam;
import io.jenkins.plugins.mcp.server.approval.BuildApprovalRequest;
import io.jenkins.plugins.mcp.server.approval.BuildApprovalService;
import io.jenkins.plugins.mcp.server.extensions.McpDtos.BuildInfo;
import io.jenkins.plugins.mcp.server.extensions.McpDtos.JobInfo;
import io.jenkins.plugins.mcp.server.extensions.McpDtos.RebuildResult;
import io.jenkins.plugins.mcp.server.tool.JenkinsMcpContext;
import jakarta.annotation.Nullable;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import jenkins.model.Jenkins;
import jenkins.model.ParameterizedJobMixIn;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.kohsuke.stapler.export.Exported;

@Extension
@Slf4j
public class DefaultMcpServer implements McpServerExtension {

    public static final String FULL_NAME = "fullName";

    @Tool(
            description = "Get a specific build or the last build of a Jenkins job. Returns build details including the parameters used for that build.",
            annotations = @Tool.Annotations(destructiveHint = false))
    public BuildInfo getBuild(
            @ToolParam(description = "Job full name of the Jenkins job (e.g., 'folder/job-name')") String jobFullName,
            @Nullable
                    @ToolParam(
                            description = "Build number (optional, if not provided, returns the last build)",
                            required = false)
                    Integer buildNumber) {
        return getBuildByNumberOrLast(jobFullName, buildNumber)
                .map(BuildInfo::fromRun)
                .orElse(null);
    }

    @Tool(
            description = "Get a Jenkins job by its full path. Returns job details including all parameter definitions with their types, default values, and choices.",
            annotations = @Tool.Annotations(destructiveHint = false))
    public JobInfo getJob(
            @ToolParam(description = "Job full name of the Jenkins job (e.g., 'folder/job-name')") String jobFullName) {
        Job<?, ?> job = Jenkins.get().getItemByFullName(jobFullName, Job.class);
        if (job == null) {
            return null;
        }
        return JobInfo.fromJob(job);
    }

    @Tool(
            description = "Rebuild a previous build with the same parameters. Uses the parameters from the specified build (or last build if not specified). This is useful to retry a failed build with identical configuration.",
            annotations = @Tool.Annotations(destructiveHint = true))
    public RebuildResult rebuildBuild(
            @ToolParam(description = "Job full name of the Jenkins job (e.g., 'folder/job-name')") String jobFullName,
            @Nullable
                    @ToolParam(
                            description = "Build number to rebuild (optional, if not provided, rebuilds the last build)",
                            required = false)
                    Integer buildNumber) {
        
        var job = Jenkins.get().getItemByFullName(jobFullName, ParameterizedJobMixIn.ParameterizedJob.class);
        if (job == null) {
            return RebuildResult.failure("Job not found: " + jobFullName);
        }
        
        job.checkPermission(Item.BUILD);
        
        // Get the build to rebuild
        @SuppressWarnings("unchecked")
        Optional<Run<?, ?>> optBuild = (Optional<Run<?, ?>>) (Optional<?>) getBuildByNumberOrLast(jobFullName, buildNumber);
        if (optBuild.isEmpty()) {
            return RebuildResult.failure("Build not found for job: " + jobFullName);
        }
        
        Run<?, ?> buildToRebuild = optBuild.get();
        
        // Extract parameters from the previous build
        ParametersAction paramsAction = buildToRebuild.getAction(ParametersAction.class);
        Map<String, Object> paramsUsed = new HashMap<>();
        
        var remoteAddr = JenkinsMcpContext.get().getHttpServletRequest().getRemoteAddr();
        CauseAction causeAction = new CauseAction(
                new MCPCause(remoteAddr), 
                new Cause.UserIdCause(),
                new RebuildCause(buildToRebuild.getNumber()));
        
        if (paramsAction != null && !paramsAction.getParameters().isEmpty()) {
            // Rebuild with same parameters
            List<ParameterValue> paramValues = paramsAction.getParameters();
            for (ParameterValue pv : paramValues) {
                if (!pv.isSensitive()) {
                    paramsUsed.put(pv.getName(), pv.getValue());
                } else {
                    paramsUsed.put(pv.getName(), "********");
                }
            }
            var future = job.scheduleBuild2(0, new ParametersAction(paramValues), causeAction);
            if (future != null) {
                return RebuildResult.success(buildToRebuild.getNumber(), paramsUsed);
            }
        } else {
            // No parameters, just trigger
            var future = job.scheduleBuild2(0, causeAction);
            if (future != null) {
                return RebuildResult.success(buildToRebuild.getNumber(), paramsUsed);
            }
        }
        
        return RebuildResult.failure("Failed to queue build. The job may be disabled or already in queue.");
    }

    /**
     * A {@link Cause} that indicates a Jenkins build was triggered as a rebuild via MCP.
     */
    @Data
    @AllArgsConstructor
    public static class RebuildCause extends Cause {
        private int originalBuildNumber;

        @Exported(visibility = 3)
        public int getOriginalBuildNumber() {
            return this.originalBuildNumber;
        }

        @Override
        public String getShortDescription() {
            return "Rebuild of #" + originalBuildNumber + " via MCP";
        }
    }

    /**
     * A {@link Cause} that indicates a Jenkins build was triggered via MCP call.
     * <p>
     * This is useful for the end user to understand that the call was trigger through this plugin's code
     * and not some manual user intervention.</p>
     * <p>And among others, it allows plugins like
     * <a href="https://plugins.jenkins.io/buildtriggerbadge/">...</a> to offer custom badges</p>
     *
     * @see #triggerBuild(String, Map)
     */
    @Data
    @AllArgsConstructor
    public static class MCPCause extends Cause {
        private String addr;

        @Exported(visibility = 3)
        public String getAddr() {
            return this.addr;
        }

        @Override
        public String getShortDescription() {
            return "Triggered via MCP Client from " + addr;
        }
    }

    @Tool(description = "Trigger a build for a Jenkins job") // keep the default value for destructive (true)
    public TriggerBuildResult triggerBuild(
            @ToolParam(description = "Full path of the Jenkins job (e.g., 'folder/job-name')") String jobFullName,
            @ToolParam(description = "Build parameters (optional, e.g., {key1=value1,key2=value2})", required = false)
                    Map<String, Object> parameters) {
        var job = Jenkins.get().getItemByFullName(jobFullName, ParameterizedJobMixIn.ParameterizedJob.class);

        if (job == null) {
            return new TriggerBuildResult(false, null, null, "Job not found: " + jobFullName);
        }
        
        job.checkPermission(Item.BUILD);
        
        // Check if job requires approval
        BuildApprovalService approvalService = BuildApprovalService.get();
        if (approvalService.requiresApproval(jobFullName)) {
            User currentUser = User.current();
            BuildApprovalRequest approval = approvalService.createApprovalRequest(
                    jobFullName, parameters, currentUser);
            
            String jenkinsUrl = Jenkins.get().getRootUrl();
            if (jenkinsUrl == null) {
                jenkinsUrl = "http://localhost:8080/";
            }
            String approvalUrl = jenkinsUrl + "mcp/approval/" + approval.getId();
            
            return new TriggerBuildResult(
                    false, 
                    approval.getId(), 
                    approvalUrl,
                    "⚠️ This job is protected and requires admin approval. " +
                    "Request ID: " + approval.getId() + ". " +
                    "An admin has been notified and must approve before the build can proceed. " +
                    "Approval URL: " + approvalUrl
            );
        }
        
        // Proceed with normal build
        var remoteAddr = JenkinsMcpContext.get().getHttpServletRequest().getRemoteAddr();
        CauseAction action = new CauseAction(new MCPCause(remoteAddr), new Cause.UserIdCause());
        
        if (job.isParameterized() && job instanceof Job j) {
            ParametersDefinitionProperty parametersDefinition =
                    (ParametersDefinitionProperty) j.getProperty(ParametersDefinitionProperty.class);
            
            if (parametersDefinition == null) {
                // Job says it's parameterized but has no parameter definitions
                var future = job.scheduleBuild2(0, action);
                if (future == null) {
                    return new TriggerBuildResult(false, null, null, 
                            "Failed to queue build. Job may be disabled, already in queue, or blocked by configuration.");
                }
                return new TriggerBuildResult(true, null, null, "Build triggered successfully");
            }
            
            var parameterValues = parametersDefinition.getParameterDefinitions().stream()
                    .map(param -> {
                        if (parameters != null && parameters.containsKey(param.getName())) {
                            var value = parameters.get(param.getName());
                            return createParameterValue(param, value);
                        } else {
                            return param.getDefaultParameterValue();
                        }
                    })
                    .filter(Objects::nonNull)
                    .toList();
            
            // Check for missing required parameters (those with null default that weren't provided)
            var missingParams = parametersDefinition.getParameterDefinitions().stream()
                    .filter(param -> {
                        boolean provided = parameters != null && parameters.containsKey(param.getName());
                        boolean hasDefault = param.getDefaultParameterValue() != null;
                        return !provided && !hasDefault;
                    })
                    .map(hudson.model.ParameterDefinition::getName)
                    .toList();
            
            if (!missingParams.isEmpty()) {
                return new TriggerBuildResult(false, null, null, 
                        "Missing required parameters with no default value: " + String.join(", ", missingParams) + 
                        ". Please provide values for these parameters.");
            }
            
            var future = job.scheduleBuild2(0, new ParametersAction(parameterValues), action);
            if (future == null) {
                return new TriggerBuildResult(false, null, null, 
                        "Failed to queue build. Job may be disabled, already in queue, or blocked by configuration (throttle, quiet period, etc.).");
            }
            return new TriggerBuildResult(true, null, null, "Build triggered successfully");
        } else {
            var future = job.scheduleBuild2(0, action);
            if (future == null) {
                return new TriggerBuildResult(false, null, null, 
                        "Failed to queue build. Job may be disabled, already in queue, or blocked by configuration.");
            }
            return new TriggerBuildResult(true, null, null, "Build triggered successfully");
        }
    }
    
    /**
     * Result of a triggerBuild request.
     * If the job requires approval, triggered=false and approvalId is set.
     */
    @Data
    @AllArgsConstructor
    public static class TriggerBuildResult {
        private boolean triggered;
        @Nullable private String approvalId;
        @Nullable private String approvalUrl;
        private String message;
    }

    @Tool(
            description =
                    "Get a paginated list of Jenkins jobs, sorted by name. Returns up to 'limit' jobs starting from the 'skip' index. If no jobs are available in the requested range, returns an empty list.",
            annotations = @Tool.Annotations(destructiveHint = false))
    public List<Job> getJobs(
            @ToolParam(
                            description =
                                    "The full path of the Jenkins folder (e.g., 'folder'), if not specified, it returns the items under root",
                            required = false)
                    String parentFullName,
            @ToolParam(
                            description = "The 0 based started index, if not specified, then start from the first (0)",
                            required = false)
                    Integer skip,
            @ToolParam(
                            description =
                                    "The maximum number of items to return. If not specified, returns 10 items. Cannot exceed 10 items.",
                            required = false)
                    Integer limit) {

        if (skip == null || skip < 0) {
            skip = 0;
        }
        if (limit == null || limit < 0 || limit > 10) {
            limit = 10;
        }
        ItemGroup parent = null;
        if (parentFullName == null || parentFullName.isEmpty()) {
            parent = Jenkins.get();
        } else {
            var fullNameItem = Jenkins.get().getItemByFullName(parentFullName, AbstractItem.class);
            if (fullNameItem instanceof ItemGroup) {
                parent = (ItemGroup) fullNameItem;
            }
        }
        if (parent != null) {
            return parent.getItemsStream()
                    .sorted(Comparator.comparing(Item::getName))
                    .skip(skip)
                    .limit(limit)
                    .toList();
        } else {
            return List.of();
        }
    }

    @Tool(description = "Update build display name and/or description") // keep the default value for destructive (true)
    @SneakyThrows
    public boolean updateBuild(
            @ToolParam(description = "Full path of the Jenkins job (e.g., 'folder/job-name')") String jobFullName,
            @Nullable
                    @ToolParam(
                            description = "Build number (optional, if not provided, updates the last build)",
                            required = false)
                    Integer buildNumber,
            @Nullable @ToolParam(description = "New display name for the build", required = false) String displayName,
            @Nullable @ToolParam(description = "New description for the build", required = false) String description) {

        var optBuild = getBuildByNumberOrLast(jobFullName, buildNumber);
        boolean updated = false;
        if (optBuild.isPresent()) {
            var build = optBuild.get();
            if (displayName != null && !displayName.isEmpty()) {
                build.setDisplayName(displayName);
                updated = true;
            }
            if (description != null && !description.isEmpty()) {
                build.setDescription(description);
                updated = true;
            }
        }

        return updated;
    }

    @Tool(
            description =
                    "Get information about the currently authenticated user, including their full name or 'anonymous' if not authenticated",
            annotations = @Tool.Annotations(destructiveHint = false))
    @SneakyThrows
    public Map<String, String> whoAmI() {
        return Optional.ofNullable(User.current())
                .map(user -> Map.of(FULL_NAME, user.getFullName()))
                .orElse(Map.of(FULL_NAME, "anonymous"));
    }

    @Tool(
            description =
                    "Checks the health and readiness status of a Jenkins instance, including whether it's in quiet"
                            + " mode, has active administrative monitors, current queue size, root URL Status, and available executor capacity."
                            + " This tool provides a comprehensive overview of the controller's operational state to determine if"
                            + " it's stable and ready to build. Use this tool to assess Jenkins instance health rather than"
                            + " simple up/down status.",
            annotations = @Tool.Annotations(destructiveHint = false))
    public Map<String, Object> getStatus() {
        var map = new HashMap<String, Object>();
        var jenkins = Jenkins.get();
        var quietMode = jenkins.isQuietingDown();
        var queue = jenkins.getQueue();
        var availableExecutors = Arrays.stream(jenkins.getComputers())
                .filter(Computer::isOnline)
                .map(Computer::countExecutors)
                .reduce(0, Integer::sum);

        map.put("Quiet Mode", quietMode);
        if (quietMode) {
            map.put(
                    "Quiet Mode reason",
                    jenkins.getQuietDownReason() != null ? jenkins.getQuietDownReason() : "Unknown");
        }
        map.put("Full Queue Size", queue.getItems().length);
        map.put("Buildable Queue Size", queue.countBuildableItems());
        map.put("Available executors (any label)", availableExecutors);
        // Tell me which clouds are defined as they can be used to provision ephemeral agents
        if (Jenkins.get().hasAnyPermission(Jenkins.SYSTEM_READ)) {
            map.put(
                    "Defined clouds that can provide agents (any label)",
                    jenkins.clouds.stream()
                            .filter(cloud -> cloud.canProvision(new Cloud.CloudState(null, 1)))
                            .map(Cloud::getDisplayName)
                            .toList());
        }
        // getActiveAdministrativeMonitors is already protected, so no need to check the user
        map.put(
                "Active administrative monitors",
                jenkins.getActiveAdministrativeMonitors().stream()
                        .map(AdministrativeMonitor::getDisplayName)
                        .toList());

        // Explicit root URL health check
        if (jenkins.getRootUrl() == null || jenkins.getRootUrl().isEmpty()) {
            map.put(
                    "Root URL Status",
                    "ERROR: Jenkins root URL is not configured. Please configure the Jenkins URL under \"Manage Jenkins → Configure System → Jenkins Location\" so tools like getJobs can work properly.\n ");
        } else {
            map.put("Root URL Status", "OK");
        }
        return map;
    }
}
