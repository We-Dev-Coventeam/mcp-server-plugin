package io.jenkins.plugins.mcp.server.approval;

import hudson.Extension;
import hudson.model.RootAction;
import hudson.model.User;
import hudson.security.Permission;
import hudson.security.PermissionGroup;
import hudson.security.PermissionScope;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.HttpResponses;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.interceptor.RequirePOST;
import org.kohsuke.stapler.verb.GET;
import org.kohsuke.stapler.verb.POST;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;

/**
 * REST API and UI for managing MCP build approval requests.
 * 
 * Endpoints:
 * - GET  /mcp/approval/               - List pending approvals (UI)
 * - GET  /mcp/approval/{id}           - View approval details (UI)
 * - POST /mcp/approval/{id}/approve   - Approve and trigger build
 * - POST /mcp/approval/{id}/reject    - Reject the request
 * - GET  /mcp/approval/api/pending    - JSON list of pending approvals
 * - GET  /mcp/approval/api/{id}       - JSON approval details
 */
@Extension
public class BuildApprovalAction implements RootAction {

    public static final PermissionGroup PERMISSIONS = new PermissionGroup(
            BuildApprovalAction.class, Messages._BuildApprovalAction_Permissions_Title());
    
    public static final Permission APPROVE = new Permission(
            PERMISSIONS, 
            "Approve",
            Messages._BuildApprovalAction_Approve_Description(),
            Jenkins.ADMINISTER,
            PermissionScope.JENKINS);

    @Override
    public String getIconFileName() {
        return hasApprovePermission() ? "symbol-lock-closed" : null;
    }

    @Override
    public String getDisplayName() {
        return "MCP Build Approvals";
    }

    @Override
    public String getUrlName() {
        return "mcp/approval";
    }
    
    private boolean hasApprovePermission() {
        return Jenkins.get().hasPermission(APPROVE) || Jenkins.get().hasPermission(Jenkins.ADMINISTER);
    }
    
    private void checkApprovePermission() {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
    }

    /**
     * Get all pending approval requests.
     */
    public List<BuildApprovalRequest> getPendingApprovals() {
        return BuildApprovalService.get().getPendingApprovals();
    }

    /**
     * Get a specific approval request by ID.
     */
    public BuildApprovalRequest getApproval(String id) {
        return BuildApprovalService.get().getApprovalRequest(id);
    }

    /**
     * Dynamic URL handling for /mcp/approval/{id}
     */
    public ApprovalDetail getDynamic(String id, StaplerRequest2 req, StaplerResponse2 rsp) {
        BuildApprovalRequest request = BuildApprovalService.get().getApprovalRequest(id);
        if (request == null) {
            return null;
        }
        return new ApprovalDetail(request);
    }

    /**
     * JSON API: List pending approvals
     * GET /mcp/approval/api/pending
     */
    @GET
    public HttpResponse doApiPending() {
        checkApprovePermission();
        List<BuildApprovalRequest> pending = getPendingApprovals();
        JSONObject response = new JSONObject();
        response.put("count", pending.size());
        response.put("approvals", pending.stream().map(this::toJson).toList());
        return jsonResponse(response);
    }

    /**
     * JSON API: Get approval by ID
     * GET /mcp/approval/api/{id}
     */
    @GET
    public HttpResponse doApiGet(@QueryParameter String id) {
        checkApprovePermission();
        BuildApprovalRequest request = getApproval(id);
        if (request == null) {
            return HttpResponses.notFound();
        }
        return jsonResponse(toJson(request));
    }
    
    /**
     * Create a JSON HTTP response.
     */
    private HttpResponse jsonResponse(JSONObject json) {
        return new HttpResponse() {
            @Override
            public void generateResponse(org.kohsuke.stapler.StaplerRequest2 req, 
                                        org.kohsuke.stapler.StaplerResponse2 rsp, 
                                        Object node) throws IOException, ServletException {
                rsp.setContentType("application/json;charset=UTF-8");
                rsp.setStatus(HttpServletResponse.SC_OK);
                PrintWriter writer = rsp.getWriter();
                writer.print(json.toString());
                writer.flush();
            }
        };
    }

    private JSONObject toJson(BuildApprovalRequest request) {
        JSONObject json = new JSONObject();
        json.put("id", request.getId());
        json.put("jobFullName", request.getJobFullName());
        json.put("parameters", request.getParameters());
        json.put("requesterId", request.getRequesterId());
        json.put("requesterDisplayName", request.getRequesterDisplayName());
        json.put("status", request.getStatus().name());
        json.put("createdAt", request.getCreatedAt().toString());
        json.put("expiresAt", request.getExpiresAt().toString());
        json.put("timeRemainingSeconds", request.getTimeRemainingSeconds());
        if (request.getApprovedBy() != null) {
            json.put("approvedBy", request.getApprovedBy());
        }
        if (request.getRejectedBy() != null) {
            json.put("rejectedBy", request.getRejectedBy());
            json.put("rejectionReason", request.getRejectionReason());
        }
        if (request.getTriggeredBuildNumber() != null) {
            json.put("triggeredBuildNumber", request.getTriggeredBuildNumber());
        }
        return json;
    }

    /**
     * Wrapper for approval detail page
     */
    public class ApprovalDetail {
        private final BuildApprovalRequest request;

        public ApprovalDetail(BuildApprovalRequest request) {
            this.request = request;
        }

        public BuildApprovalRequest getRequest() {
            return request;
        }

        public String getDisplayName() {
            return "Approval: " + request.getJobFullName();
        }

        /**
         * Approve the request
         * POST /mcp/approval/{id}/approve
         */
        @RequirePOST
        public HttpResponse doApprove(StaplerRequest2 req, StaplerResponse2 rsp) 
                throws IOException, ServletException {
            checkApprovePermission();
            
            User currentUser = User.current();
            Integer buildNumber = BuildApprovalService.get().approveAndTrigger(request.getId(), currentUser);
            
            if (buildNumber != null) {
                String jobUrl = Jenkins.get().getRootUrl() + "job/" + 
                        request.getJobFullName().replace("/", "/job/");
                return HttpResponses.redirectTo(jobUrl);
            } else {
                return HttpResponses.error(400, "Failed to approve request. It may have expired.");
            }
        }

        /**
         * Reject the request
         * POST /mcp/approval/{id}/reject
         */
        @RequirePOST
        public HttpResponse doReject(StaplerRequest2 req, StaplerResponse2 rsp,
                @QueryParameter String reason) throws IOException, ServletException {
            checkApprovePermission();
            
            User currentUser = User.current();
            boolean success = BuildApprovalService.get().reject(
                    request.getId(), currentUser, reason != null ? reason : "Rejected by admin");
            
            if (success) {
                return HttpResponses.redirectTo("../..");
            } else {
                return HttpResponses.error(400, "Failed to reject request. It may have already been processed.");
            }
        }
    }
}
