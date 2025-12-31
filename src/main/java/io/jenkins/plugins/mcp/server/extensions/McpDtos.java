package io.jenkins.plugins.mcp.server.extensions;

import hudson.model.Job;
import hudson.model.ParameterDefinition;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.Run;
import hudson.model.ParametersAction;
import hudson.model.ParameterValue;
import lombok.Data;

import jakarta.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Data Transfer Objects for MCP responses.
 * These DTOs provide richer information than raw Jenkins objects,
 * especially for parameters which are critical for AI/LLM usage.
 */
public class McpDtos {

    /**
     * Rich job information including parameter definitions.
     */
    @Data
    public static class JobInfo {
        private String fullName;
        private String name;
        private String displayName;
        private String url;
        private String description;
        private boolean buildable;
        private boolean inQueue;
        private boolean parameterized;
        private Integer lastBuildNumber;
        private String lastBuildResult;
        private List<ParameterInfo> parameters;

        public static JobInfo fromJob(Job<?, ?> job) {
            JobInfo info = new JobInfo();
            info.setFullName(job.getFullName());
            info.setName(job.getName());
            info.setDisplayName(job.getDisplayName());
            info.setUrl(job.getAbsoluteUrl());
            info.setDescription(job.getDescription());
            info.setBuildable(job.isBuildable());
            info.setInQueue(job.isInQueue());
            
            // Parameters
            ParametersDefinitionProperty paramsDef = job.getProperty(ParametersDefinitionProperty.class);
            info.setParameterized(paramsDef != null && !paramsDef.getParameterDefinitions().isEmpty());
            
            List<ParameterInfo> paramList = new ArrayList<>();
            if (paramsDef != null) {
                for (ParameterDefinition def : paramsDef.getParameterDefinitions()) {
                    paramList.add(ParameterInfo.fromDefinition(def));
                }
            }
            info.setParameters(paramList);
            
            // Last build info
            Run<?, ?> lastBuild = job.getLastBuild();
            if (lastBuild != null) {
                info.setLastBuildNumber(lastBuild.getNumber());
                if (lastBuild.getResult() != null) {
                    info.setLastBuildResult(lastBuild.getResult().toString());
                } else {
                    info.setLastBuildResult("IN_PROGRESS");
                }
            }
            
            return info;
        }
    }

    /**
     * Parameter definition info.
     */
    @Data
    public static class ParameterInfo {
        private String name;
        private String type;
        private String description;
        @Nullable private Object defaultValue;
        @Nullable private List<String> choices; // For choice parameters

        public static ParameterInfo fromDefinition(ParameterDefinition def) {
            ParameterInfo info = new ParameterInfo();
            info.setName(def.getName());
            info.setType(def.getClass().getSimpleName().replace("ParameterDefinition", ""));
            info.setDescription(def.getDescription());
            
            // Get default value
            ParameterValue defaultVal = def.getDefaultParameterValue();
            if (defaultVal != null) {
                info.setDefaultValue(defaultVal.getValue());
            }
            
            // Get choices for ChoiceParameterDefinition
            if (def instanceof hudson.model.ChoiceParameterDefinition choiceDef) {
                info.setChoices(choiceDef.getChoices());
            }
            
            return info;
        }
    }

    /**
     * Rich build information including parameters used.
     */
    @Data
    public static class BuildInfo {
        private String jobFullName;
        private int number;
        private String displayName;
        private String url;
        private String result;
        private boolean building;
        private long timestamp;
        private long duration;
        private long estimatedDuration;
        private String description;
        private List<BuildParameterValue> parameters;
        private List<String> causes;

        public static BuildInfo fromRun(Run<?, ?> run) {
            BuildInfo info = new BuildInfo();
            info.setJobFullName(run.getParent().getFullName());
            info.setNumber(run.getNumber());
            info.setDisplayName(run.getDisplayName());
            info.setUrl(run.getAbsoluteUrl());
            info.setResult(run.getResult() != null ? run.getResult().toString() : "IN_PROGRESS");
            info.setBuilding(run.isBuilding());
            info.setTimestamp(run.getTimeInMillis());
            info.setDuration(run.getDuration());
            info.setEstimatedDuration(run.getEstimatedDuration());
            info.setDescription(run.getDescription());
            
            // Extract parameters
            List<BuildParameterValue> paramList = new ArrayList<>();
            ParametersAction paramsAction = run.getAction(ParametersAction.class);
            if (paramsAction != null) {
                for (ParameterValue pv : paramsAction.getParameters()) {
                    paramList.add(BuildParameterValue.fromParameterValue(pv));
                }
            }
            info.setParameters(paramList);
            
            // Extract causes
            List<String> causeList = new ArrayList<>();
            for (var cause : run.getCauses()) {
                causeList.add(cause.getShortDescription());
            }
            info.setCauses(causeList);
            
            return info;
        }
    }

    /**
     * Parameter value as used in a specific build.
     */
    @Data
    public static class BuildParameterValue {
        private String name;
        private String type;
        @Nullable private Object value;
        private boolean sensitive;

        public static BuildParameterValue fromParameterValue(ParameterValue pv) {
            BuildParameterValue bpv = new BuildParameterValue();
            bpv.setName(pv.getName());
            bpv.setType(pv.getClass().getSimpleName().replace("ParameterValue", ""));
            bpv.setSensitive(pv.isSensitive());
            
            // Don't expose sensitive values
            if (pv.isSensitive()) {
                bpv.setValue("********");
            } else {
                bpv.setValue(pv.getValue());
            }
            
            return bpv;
        }
    }

    /**
     * Result of a rebuild operation.
     */
    @Data
    public static class RebuildResult {
        private boolean success;
        private String message;
        @Nullable private Integer queueItemId;
        @Nullable private Map<String, Object> parametersUsed;

        public static RebuildResult success(int queueItemId, Map<String, Object> params) {
            RebuildResult result = new RebuildResult();
            result.setSuccess(true);
            result.setMessage("Build queued successfully");
            result.setQueueItemId(queueItemId);
            result.setParametersUsed(params);
            return result;
        }

        public static RebuildResult failure(String message) {
            RebuildResult result = new RebuildResult();
            result.setSuccess(false);
            result.setMessage(message);
            return result;
        }
    }
}
