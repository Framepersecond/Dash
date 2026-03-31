package dash.web;

import dash.WebAuth;

import java.util.Map;

public class UsersPage {

    public static String render(Map<String, WebAuth.UserInfo> users, Iterable<String> roles, Map<String, Integer> roleValues,
            String actorUsername, boolean actorIsMainAdmin, String generatedCode, String message,
            java.util.List<dash.RegistrationApprovalManager.PendingRegistration> pendingRegistrations,
            java.util.List<WebAuth.UserInfo> pendingBridgeUsers) {

        StringBuilder roleOptions = new StringBuilder();
        for (String role : roles) {
            roleOptions.append("<option value='").append(role).append("'>").append(role).append("</option>");
        }

        int highestRoleValue = 0;
        for (Integer value : roleValues.values()) {
            if (value != null) {
                highestRoleValue = Math.max(highestRoleValue, value);
            }
        }

        int actorRoleValue = 0;
        WebAuth.UserInfo actorInfo = users.get(actorUsername);
        if (actorIsMainAdmin) {
            actorRoleValue = highestRoleValue + 1;
        } else if (actorInfo != null) {
            actorRoleValue = roleValues.getOrDefault(actorInfo.role().toUpperCase(), 0);
        }

        StringBuilder usersHtml = new StringBuilder();
        for (WebAuth.UserInfo user : users.values()) {
            String username = user.username();
            String displayName = toDisplayName(username);
            String mainBadge = user.mainAdmin()
                    ? "<span class='ml-2 px-2 py-0.5 rounded bg-amber-500/20 text-amber-300 text-[10px]'>MAIN ADMIN</span>"
                    : "";
            String bridgeBadge = user.bridgeUser()
                    ? "<span class='ml-2 text-green-500 font-bold bg-green-900/30 px-2 py-0.5 rounded text-[10px]'>NEODASH</span>"
                    : "";
            String selectedRole = "MAIN_ADMIN".equalsIgnoreCase(user.role()) ? "ADMIN" : user.role();
            int userRoleValue = user.mainAdmin() ? highestRoleValue + 1 : roleValues.getOrDefault(selectedRole.toUpperCase(), 0);
            boolean canManageThisUser = actorIsMainAdmin || (!user.mainAdmin() && userRoleValue < actorRoleValue);

            StringBuilder perUserRoleOptions = new StringBuilder();
            for (String role : roles) {
                int candidateValue = roleValues.getOrDefault(role.toUpperCase(), 0);
                boolean assignable = actorIsMainAdmin || candidateValue < actorRoleValue;
                boolean selected = role.equalsIgnoreCase(selectedRole);
                perUserRoleOptions.append("<option value='").append(role).append("'")
                        .append(assignable ? "" : " disabled")
                        .append(selected ? " selected" : "")
                        .append(">")
                        .append(role)
                        .append(" (v=").append(candidateValue).append(")")
                        .append("</option>");
            }

            String disableClass = canManageThisUser ? "" : " opacity-50 cursor-not-allowed";
            usersHtml.append("<div class='p-4 rounded-xl border border-white/10 bg-black/20'>")
                    .append("<div class='text-white font-semibold'>").append(displayName).append(mainBadge).append(bridgeBadge)
                    .append("</div>")
                    .append(user.bridgeUser()
                            ? "<div class='text-xs text-emerald-300 mt-1'>Origin: NeoDash SSO</div>"
                            : "<div class='text-xs text-slate-400'>Linked player: " + user.linkedPlayer() + "</div>")
                    .append("<div class='text-xs text-primary mt-1'>Role: ").append(user.role()).append(" (v=").append(userRoleValue).append(")</div>")
                    .append("<form action='/action' method='post' class='mt-3 flex gap-2'>")
                    .append("<input type='hidden' name='action' value='user_set_role'>")
                    .append("<input type='hidden' name='username' value='").append(username).append("'>")
                    .append("<select name='role'").append(canManageThisUser ? "" : " disabled")
                    .append(" class='bg-slate-900 border border-slate-700 rounded px-2 py-1 text-sm").append(disableClass).append("'>")
                    .append(perUserRoleOptions)
                    .append("</select>")
                    .append("<button").append(canManageThisUser ? "" : " disabled")
                    .append(" class='px-3 py-1 rounded bg-primary/20 text-primary text-xs").append(disableClass).append("'>Update Role</button>")
                    .append("</form>")
                    .append("<form action='/action' method='post' class='mt-2 flex gap-2'>")
                    .append("<input type='hidden' name='action' value='user_make_main_admin'>")
                    .append("<input type='hidden' name='username' value='").append(username).append("'>")
                    .append("<button").append(actorIsMainAdmin && !user.mainAdmin() ? "" : " disabled")
                    .append(" class='px-3 py-1 rounded bg-amber-500/20 text-amber-300 text-xs")
                    .append(actorIsMainAdmin && !user.mainAdmin() ? "" : " opacity-50 cursor-not-allowed")
                    .append("'>Make Main Admin</button>")
                    .append("</form>")
                    .append("<form action='/action' method='post' class='mt-2' onsubmit=\"return confirm('Delete user account?');\">")
                    .append("<input type='hidden' name='action' value='user_delete'>")
                    .append("<input type='hidden' name='username' value='").append(username).append("'>")
                    .append("<button").append(canManageThisUser ? "" : " disabled")
                    .append(" class='px-3 py-1 rounded bg-rose-500/20 text-rose-300 text-xs").append(disableClass).append("'>Delete User</button>")
                    .append("</form>")
                    .append(canManageThisUser ? "" : "<p class='text-[11px] text-slate-500 mt-2'>You can only manage users below your own rank value.</p>")
                    .append("</div>");
        }

        String codeBox = "";
        if (generatedCode != null && !generatedCode.isBlank()) {
            codeBox = "<div class='mb-4 p-3 rounded-lg bg-emerald-500/10 border border-emerald-500/20 text-emerald-300'>"
                    + "Generated invite code: <span class='font-mono font-bold'>" + generatedCode + "</span></div>";
        }

        String messageBox = "";
        if (message != null && !message.isBlank()) {
            messageBox = "<div class='mb-4 p-3 rounded-lg bg-sky-500/10 border border-sky-500/20 text-sky-200'>"
                    + message + "</div>";
        }

        StringBuilder pendingHtml = new StringBuilder();
        if (pendingRegistrations != null) {
            for (dash.RegistrationApprovalManager.PendingRegistration pending : pendingRegistrations) {
                pendingHtml.append("<div class='p-3 rounded-lg border border-amber-500/30 bg-amber-500/10'>")
                        .append("<p class='text-amber-200 text-sm font-semibold'>")
                        .append(pending.username())
                        .append(" <span class='text-[11px] text-amber-300 font-mono'>[")
                        .append(pending.id())
                        .append("]</span></p>")
                        .append("<p class='text-[11px] text-amber-100/80 mt-1'>Role: ")
                        .append(pending.role())
                        .append(" • Linked: ")
                        .append(pending.linkedPlayer())
                        .append(" • IP: ")
                        .append(pending.requestedFromIp() == null ? "-" : pending.requestedFromIp())
                        .append("</p>");
                if (actorIsMainAdmin) {
                    pendingHtml.append("<div class='flex gap-2 mt-2'>")
                            .append("<form action='/action' method='post'>")
                            .append("<input type='hidden' name='action' value='registration_approve'>")
                            .append("<input type='hidden' name='request_id' value='").append(pending.id()).append("'>")
                            .append("<button class='px-3 py-1 rounded bg-emerald-500/20 text-emerald-300 text-xs font-semibold hover:bg-emerald-500/30'>Approve</button>")
                            .append("</form>")
                            .append("<form action='/action' method='post'>")
                            .append("<input type='hidden' name='action' value='registration_deny'>")
                            .append("<input type='hidden' name='request_id' value='").append(pending.id()).append("'>")
                            .append("<button class='px-3 py-1 rounded bg-rose-500/20 text-rose-300 text-xs font-semibold hover:bg-rose-500/30'>Deny</button>")
                            .append("</form>")
                            .append("</div>");
                }
                pendingHtml.append("</div>");
            }
        }
        if (pendingHtml.length() == 0) {
            pendingHtml.append("<p class='text-xs text-slate-500'>No pending registrations.</p>");
        }

        StringBuilder pendingBridgeHtml = new StringBuilder();
        if (pendingBridgeUsers != null) {
            for (WebAuth.UserInfo pendingBridge : pendingBridgeUsers) {
                String username = pendingBridge.username();
                String displayName = toDisplayName(username);
                pendingBridgeHtml.append("<div class='p-3 rounded-lg border border-sky-500/30 bg-sky-500/10'>")
                        .append("<p class='text-sky-200 text-sm font-semibold'>")
                        .append(displayName)
                        .append(" <span class='text-[11px] text-sky-300'>[NeoDash]</span></p>")
                        .append("<p class='text-[11px] text-sky-100/80 mt-1'>Status: waiting for approval</p>");

                if (actorIsMainAdmin) {
                    pendingBridgeHtml.append("<form action='/action' method='post' class='flex flex-wrap gap-2 mt-2'>")
                            .append("<input type='hidden' name='action' value='bridge_user_allow'>")
                            .append("<input type='hidden' name='username' value='").append(username).append("'>")
                            .append("<select name='role' class='bg-slate-900 border border-slate-700 rounded px-2 py-1 text-xs'>")
                            .append(roleOptions)
                            .append("</select>")
                            .append("<input name='permissions' placeholder='Extra perms CSV (optional)' class='bg-slate-900 border border-slate-700 rounded px-2 py-1 text-xs'>")
                            .append("<button class='px-3 py-1 rounded bg-emerald-500/20 text-emerald-300 text-xs font-semibold hover:bg-emerald-500/30'>Approve &amp; Assign Role</button>")
                            .append("</form>")
                            .append("<form action='/action' method='post' class='mt-2'>")
                            .append("<input type='hidden' name='action' value='bridge_user_deny'>")
                            .append("<input type='hidden' name='username' value='").append(username).append("'>")
                            .append("<button class='px-3 py-1 rounded bg-rose-500/20 text-rose-300 text-xs font-semibold hover:bg-rose-500/30'>Deny</button>")
                            .append("</form>");
                }
                pendingBridgeHtml.append("</div>");
            }
        }
        if (pendingBridgeHtml.length() == 0) {
            pendingBridgeHtml.append("<p class='text-xs text-slate-500'>No pending bridge users.</p>");
        }

        String content = HtmlTemplate.statsHeader()
                + "<main class='flex-1 p-6 overflow-auto'><div class='rounded-3xl bg-glass-surface backdrop-blur-xl border border-glass-border p-6'>"
                + "<h2 class='text-xl font-bold text-white mb-4'>User & Role Management</h2>"
                + codeBox
                + messageBox
                + "<div class='mb-6 p-4 rounded-xl border border-white/10 bg-black/20'>"
                + "<h3 class='text-sm font-bold text-white uppercase tracking-wider mb-2'>Pending Registration Approval</h3>"
                + (actorIsMainAdmin
                        ? "<p class='text-xs text-slate-400 mb-3'>MAIN_ADMIN must confirm new registrations here.</p>"
                        : "<p class='text-xs text-slate-500 mb-3'>Read-only: only MAIN_ADMIN can approve.</p>")
                + "<div class='flex flex-col gap-2'>" + pendingHtml + "</div>"
                + "</div>"
                + "<div class='mb-6 p-4 rounded-xl border border-white/10 bg-black/20'>"
                + "<h3 class='text-sm font-bold text-white uppercase tracking-wider mb-2'>PENDING NEODASH SSO BRIDGE USERS</h3>"
                + (actorIsMainAdmin
                        ? "<p class='text-xs text-slate-400 mb-3'>Allow requires role selection and optional extra permissions.</p>"
                        : "<p class='text-xs text-slate-500 mb-3'>Read-only: only MAIN_ADMIN can approve or deny bridge users.</p>")
                + "<div class='flex flex-col gap-2'>" + pendingBridgeHtml + "</div>"
                + "</div>"
                + "<form action='/action' method='post' class='grid grid-cols-1 md:grid-cols-4 gap-3 mb-6'>"
                + "<input type='hidden' name='action' value='invite_generate'>"
                + "<input name='player' placeholder='Target player name (optional)' class='bg-slate-900 border border-slate-700 rounded px-3 py-2'>"
                + "<select name='role' class='bg-slate-900 border border-slate-700 rounded px-3 py-2'>" + roleOptions + "</select>"
                + "<input name='permissions' placeholder='Extra perms, comma-separated' class='bg-slate-900 border border-slate-700 rounded px-3 py-2'>"
                + "<button class='bg-primary text-black rounded px-4 py-2 font-semibold'>Generate Invite Code</button>"
                + "</form>"
                + "<div class='grid grid-cols-1 md:grid-cols-2 gap-3'>" + usersHtml + "</div>"
                + "</div></main>"
                + HtmlTemplate.statsScript();

        return HtmlTemplate.page("Users", "/users", content);
    }

    private static String toDisplayName(String username) {
        if (username == null) {
            return "";
        }
        if (username.startsWith("nd_") && username.length() > 3) {
            return username.substring(3);
        }
        return username;
    }
}

