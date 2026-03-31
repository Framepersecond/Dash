package dash.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class PermissionsPage {

    private static final List<String> KNOWN_PERMISSIONS = List.of(
            "dash.web.*",
            "dash.web.stats.read",
            "dash.web.console.read",
            "dash.web.console.command",
            "dash.web.server.control",
            "dash.web.players.read",
            "dash.web.players.moderate",
            "dash.web.players.kick",
            "dash.web.players.ban",
            "dash.web.players.notes",
            "dash.web.players.inventory.write",
            "dash.web.files.read",
            "dash.web.files.write",
            "dash.web.plugins.read",
            "dash.web.plugins.manage",
            "dash.web.users.manage",
            "dash.web.settings.read",
            "dash.web.settings.write",
            "dash.web.settings.gamerule.keep_inventory",
            "dash.web.settings.gamerule.mob_spawning",
            "dash.web.settings.gamerule.daylight_cycle",
            "dash.web.settings.gamerule.weather_cycle",
            "dash.web.settings.gamerule.mob_griefing",
            "dash.web.settings.gamerule.fire_tick",
            "dash.web.settings.gamerule.natural_regeneration",
            "dash.web.settings.distance.view",
            "dash.web.settings.distance.simulation",
            "dash.web.settings.motd.write",
            "dash.web.settings.icon.write",
            "dash.web.whitelist.manage",
            "dash.web.backups.read",
            "dash.web.backups.create",
            "dash.web.backups.delete",
            "dash.web.backups.schedule",
            "dash.web.datapacks.write",
            "dash.web.chat.send",
            "dash.web.tools.spark",
            "dash.web.audit.read",
            "dash.web.pluginsettings.read",
            "dash.web.pluginsettings.write",
            "dash.web.tasks.read",
            "dash.web.tasks.write");

    public static String render(Map<String, List<String>> rolePermissions, Map<String, Integer> roleValues,
            String selectedRole, String message, boolean isMainAdmin, int actorRoleValue) {
        List<String> roles = new ArrayList<>(rolePermissions.keySet());
        roles.sort(String::compareToIgnoreCase);

        if (selectedRole == null || selectedRole.isBlank() || !rolePermissions.containsKey(selectedRole)) {
            selectedRole = roles.isEmpty() ? "" : roles.get(0);
        }

        List<String> activePermissions = rolePermissions.getOrDefault(selectedRole, List.of());
        boolean editingAdminRole = "ADMIN".equalsIgnoreCase(selectedRole);
        int selectedRoleValue = roleValues.getOrDefault(selectedRole == null ? "" : selectedRole.toUpperCase(), 0);
        boolean hierarchyReadOnly = !isMainAdmin && selectedRoleValue >= actorRoleValue;
        boolean readOnlyRole = "MAIN_ADMIN".equalsIgnoreCase(selectedRole)
                || (editingAdminRole && !isMainAdmin)
                || hierarchyReadOnly;

        StringBuilder roleLinks = new StringBuilder();
        for (String role : roles) {
            boolean active = role.equalsIgnoreCase(selectedRole);
            roleLinks.append("<a href='/permissions?role=").append(role)
                    .append("' class='block p-3 rounded-xl border ")
                    .append(active
                            ? "bg-primary/20 border-primary/40 text-primary"
                            : "bg-black/20 border-white/10 text-slate-300 hover:border-white/20 hover:bg-white/5")
                    .append(" transition-all'>")
                    .append("<p class='font-semibold'>").append(role).append("</p>")
                    .append("<p class='text-xs ").append(active ? "text-primary/80" : "text-slate-500").append("'>")
                    .append(rolePermissions.getOrDefault(role, List.of()).size()).append(" active permissions • value ")
                    .append(roleValues.getOrDefault(role, 0)).append("</p>")
                    .append("</a>");
        }

        StringBuilder shelf = new StringBuilder();
        for (String permission : KNOWN_PERMISSIONS) {
            boolean isActive = activePermissions.contains(permission);
            shelf.append("<button type='button' data-permission='").append(permission)
                    .append("' data-active='").append(isActive)
                    .append("' class='perm-book px-3 py-2 rounded-lg border text-left font-mono text-xs transition-all ")
                    .append(isActive
                            ? "bg-emerald-500/20 border-emerald-500/40 text-emerald-300"
                            : "bg-slate-900/70 border-slate-700 text-slate-300 hover:border-slate-500")
                    .append("'>")
                    .append(permission)
                    .append("</button>");
        }

        String messageHtml = "";
        if (message != null && !message.isBlank()) {
            messageHtml = "<div class='mb-4 p-3 rounded-lg bg-sky-500/10 border border-sky-500/20 text-sky-200 text-sm'>"
                    + message + "</div>";
        }

        String readOnlyReason = "";
        if (editingAdminRole && !isMainAdmin) {
            readOnlyReason = "Only the MAIN_ADMIN account can modify ADMIN role permissions.";
        } else if (hierarchyReadOnly) {
            readOnlyReason = "You can only manage roles below your own level.";
        } else if ("MAIN_ADMIN".equalsIgnoreCase(selectedRole)) {
            readOnlyReason = "MAIN_ADMIN is a system role and is not editable.";
        }

        String controls = "<div class='rounded-xl border border-white/10 bg-black/20 p-4'>"
                + "<h3 class='text-sm uppercase tracking-wider text-slate-400 mb-3'>Edit Permissions</h3>"
                + "<div class='flex flex-wrap gap-2 mb-3'>"
                + "<button id='mode-add' type='button'" + (readOnlyRole ? " disabled" : "")
                + " class='px-4 py-2 rounded-lg bg-primary/20 text-primary border border-primary/30 text-sm font-semibold"
                + (readOnlyRole ? " opacity-50 cursor-not-allowed" : "") + "'>Add Permission</button>"
                + "<button id='mode-remove' type='button'" + (readOnlyRole ? " disabled" : "")
                + " class='px-4 py-2 rounded-lg bg-rose-500/10 text-rose-300 border border-rose-500/30 text-sm font-semibold"
                + (readOnlyRole ? " opacity-50 cursor-not-allowed" : "") + "'>Remove Permission</button>"
                + "</div>"
                + "<p class='text-xs text-slate-500'>Green = active. Blue = mark for add. Red = mark for removal.</p>"
                + (readOnlyReason.isBlank() ? ""
                        : "<p class='text-xs text-amber-200 mt-2'>" + readOnlyReason + "</p>")
                + "</div>";

        String saveForm = "<form id='permissions-form' action='/action' method='post' class='mt-4 flex items-center gap-3'>"
                        + "<input type='hidden' name='action' value='role_permissions_save'>"
                        + "<input type='hidden' name='role' value='" + selectedRole + "'>"
                        + "<input type='hidden' id='add-perms' name='add_permissions' value=''>"
                        + "<input type='hidden' id='remove-perms' name='remove_permissions' value=''>"
                        + "<button type='submit'" + (readOnlyRole ? " disabled" : "")
                        + " class='px-4 py-2 rounded-lg bg-emerald-500/20 text-emerald-300 border border-emerald-500/30 text-sm font-semibold hover:bg-emerald-500/30"
                        + (readOnlyRole ? " opacity-50 cursor-not-allowed" : "") + "'>Save</button>"
                        + "<span id='selection-info' class='text-xs text-slate-500'>No pending changes</span>"
                        + "</form>";

        boolean systemRole = "ADMIN".equalsIgnoreCase(selectedRole) || "MODERATOR".equalsIgnoreCase(selectedRole);
        boolean canDeleteSelectedRole = !readOnlyRole && !systemRole && selectedRole != null && !selectedRole.isBlank();
        String selectedRoleManagement = selectedRole == null || selectedRole.isBlank() ? ""
                : "<div class='rounded-xl border border-white/10 bg-black/20 p-4 mt-4'>"
                        + "<h3 class='text-sm uppercase tracking-wider text-slate-400 mb-3'>Rank Value</h3>"
                        + "<form action='/action' method='post' class='flex items-end gap-2 mb-3'>"
                        + "<input type='hidden' name='action' value='role_set_value'>"
                        + "<input type='hidden' name='role' value='" + selectedRole + "'>"
                        + "<div class='flex-1'>"
                        + "<label class='text-xs text-slate-500 block mb-1'>Value (higher = stronger)</label>"
                        + "<input type='number' name='value' min='0' max='1000000' value='" + selectedRoleValue + "'"
                        + (readOnlyRole ? " disabled" : "")
                        + " class='w-full bg-slate-900 border border-slate-700 rounded px-3 py-2 text-sm text-white"
                        + (readOnlyRole ? " opacity-50 cursor-not-allowed" : "") + "'>"
                        + "</div>"
                        + "<button" + (readOnlyRole ? " disabled" : "")
                        + " class='px-3 py-2 rounded bg-primary/20 text-primary text-xs font-semibold"
                        + (readOnlyRole ? " opacity-50 cursor-not-allowed" : "") + "'>Update</button>"
                        + "</form>"
                        + "<form action='/action' method='post' onsubmit=\"return confirm('Delete rank " + selectedRole
                        + "?');\">"
                        + "<input type='hidden' name='action' value='role_delete'>"
                        + "<input type='hidden' name='role' value='" + selectedRole + "'>"
                        + "<button" + (canDeleteSelectedRole ? "" : " disabled")
                        + " class='px-3 py-1.5 rounded bg-rose-500/20 text-rose-300 text-xs font-semibold"
                        + (canDeleteSelectedRole ? " hover:bg-rose-500/30" : " opacity-50 cursor-not-allowed")
                        + "'>Delete Rank</button>"
                        + "</form>"
                        + (systemRole ? "<p class='text-[11px] text-slate-500 mt-2'>System ranks cannot be deleted.</p>" : "")
                        + "</div>";

        String presetOptions = "<option value='MODERATOR'>MODERATOR</option>"
                + (isMainAdmin ? "<option value='ADMIN'>ADMIN</option>" : "");
        String adminPresetHint = isMainAdmin
                ? ""
                : "<p class='text-[11px] text-amber-300/80 mt-1'>Only MAIN_ADMIN can use the ADMIN preset.</p>";

        String createRoleCard = "<div class='rounded-2xl bg-glass-surface backdrop-blur-xl border border-glass-border p-4 mt-4'>"
                + "<h3 class='text-sm font-bold uppercase tracking-wider text-white mb-3'>Create Custom Rank</h3>"
                + "<form id='create-role-form' action='/action' method='post' class='space-y-3'>"
                + "<input type='hidden' name='action' value='role_create'>"
                + "<div>"
                + "<label class='text-xs text-slate-400 block mb-1'>Rank Name</label>"
                + "<input id='role-name-input' name='role_name' required maxlength='64' placeholder='e.g. BUILDER TEAM' class='w-full bg-slate-900 border border-slate-700 rounded px-3 py-2 text-sm text-white placeholder-slate-500 focus:border-primary outline-none'>"
                + "<p class='text-[11px] text-slate-500 mt-1'>Any name is allowed (max 64 chars). Spaces and dots become underscores.</p>"
                + "<p class='text-[11px] text-slate-500 mt-1'>Saved as: <span id='role-name-preview' class='font-mono text-slate-300'>-</span></p>"
                + "<p id='role-name-hint' class='text-[11px] text-slate-500 mt-1'>Enter a rank name.</p>"
                + "</div>"
                + "<div>"
                + "<label class='text-xs text-slate-400 block mb-1'>Preset</label>"
                + "<select name='preset' class='w-full bg-slate-900 border border-slate-700 rounded px-3 py-2 text-sm text-white'>"
                + presetOptions
                + "</select>"
                + adminPresetHint
                + "</div>"
                + "<button id='create-role-btn' class='w-full px-4 py-2 rounded-lg bg-primary text-black text-sm font-semibold hover:bg-white transition-colors'>Create Rank</button>"
                + "<p id='create-role-status' class='hidden text-[11px] text-slate-400'>Creating rank...</p>"
                + "</form>"
                + "</div>";

        String content = HtmlTemplate.statsHeader()
                + "<main class='flex-1 p-6 overflow-auto'>"
                + "<div class='grid grid-cols-1 xl:grid-cols-4 gap-6'>"
                + "<aside class='xl:col-span-1 rounded-2xl bg-glass-surface backdrop-blur-xl border border-glass-border p-4'>"
                + "<h2 class='text-sm font-bold uppercase tracking-wider text-white mb-3'>Roles</h2>"
                + "<div class='flex flex-col gap-2'>" + roleLinks + "</div>"
                + createRoleCard
                + "</aside>"
                + "<section class='xl:col-span-3 rounded-2xl bg-glass-surface backdrop-blur-xl border border-glass-border p-5'>"
                + "<div class='flex items-center justify-between mb-4'>"
                + "<h2 class='text-xl font-bold text-white'>Permissions - " + selectedRole + "</h2>"
                + "<span class='text-xs text-slate-500'>Bookshelf view</span>"
                + "</div>"
                + messageHtml
                + "<div id='permission-shelf' class='grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-2 max-h-[460px] overflow-y-auto console-scrollbar pr-1'>"
                + shelf + "</div>"
                + "<div class='mt-6 flex items-end justify-between gap-3'>"
                + controls
                + "<div class='min-w-[240px]'>" + saveForm + "</div>"
                + "</div>"
                + selectedRoleManagement
                + "</section>"
                + "</div>"
                + "</main>"
                + HtmlTemplate.statsScript()
                + "<script>"
                + "let mode='add';"
                + "const toAdd=new Set();"
                + "const toRemove=new Set();"
                + "const addBtn=document.getElementById('mode-add');"
                + "const removeBtn=document.getElementById('mode-remove');"
                + "const saveAdd=document.getElementById('add-perms');"
                + "const saveRemove=document.getElementById('remove-perms');"
                + "const info=document.getElementById('selection-info');"
                + "const roleNameInput=document.getElementById('role-name-input');"
                + "const roleNamePreview=document.getElementById('role-name-preview');"
                + "const roleNameHint=document.getElementById('role-name-hint');"
                + "const createRoleForm=document.getElementById('create-role-form');"
                + "const createRoleBtn=document.getElementById('create-role-btn');"
                + "const createRoleStatus=document.getElementById('create-role-status');"
                + "function refreshInfo(){if(!info)return;info.textContent='Add: '+toAdd.size+' / Remove: '+toRemove.size;}"
                + "function normalizeRoleName(v){return (v||'').trim().replace(/\\s+/g,'_').replace(/\\./g,'_').toUpperCase();}"
                + "function refreshRolePreview(){"
                + "if(!roleNameInput||!roleNamePreview||!roleNameHint)return;"
                + "const normalized=normalizeRoleName(roleNameInput.value);"
                + "roleNamePreview.textContent=normalized||'-';"
                + "roleNameHint.className='text-[11px] mt-1';"
                + "if(!normalized){roleNameHint.classList.add('text-slate-500');roleNameHint.textContent='Role name cannot be empty after normalization.';return;}"
                + "if(normalized.length>64){roleNameHint.classList.add('text-rose-300');roleNameHint.textContent='Normalized role is too long ('+normalized.length+'/64).';return;}"
                + "roleNameHint.classList.add('text-emerald-300');roleNameHint.textContent='Name is valid and will be saved exactly as shown above.';"
                + "}"
                + "function markButtons(){"
                + "document.querySelectorAll('.perm-book').forEach(b=>{"
                + "const p=b.dataset.permission;const active=b.dataset.active==='true';"
                + "b.classList.remove('bg-blue-500/20','border-blue-500/40','text-blue-200','bg-rose-500/20','border-rose-500/40','text-rose-200');"
                + "if(active){b.classList.add('bg-emerald-500/20','border-emerald-500/40','text-emerald-300');}"
                + "else{b.classList.add('bg-slate-900/70','border-slate-700','text-slate-300');}"
                + "if(toAdd.has(p)){b.classList.remove('bg-slate-900/70','border-slate-700','text-slate-300');b.classList.add('bg-blue-500/20','border-blue-500/40','text-blue-200');}"
                + "if(toRemove.has(p)){b.classList.add('bg-rose-500/20','border-rose-500/40','text-rose-200');}"
                + "});"
                + "refreshInfo();"
                + "}"
                + "if(addBtn){addBtn.addEventListener('click',()=>{mode='add';addBtn.classList.add('ring-2','ring-primary/40');removeBtn.classList.remove('ring-2','ring-rose-500/30');});}"
                + "if(removeBtn){removeBtn.addEventListener('click',()=>{mode='remove';removeBtn.classList.add('ring-2','ring-rose-500/30');addBtn.classList.remove('ring-2','ring-primary/40');});}"
                + "document.querySelectorAll('.perm-book').forEach(btn=>btn.addEventListener('click',()=>{"
                + "if(" + readOnlyRole + ") return;"
                + "const perm=btn.dataset.permission;const active=btn.dataset.active==='true';"
                + "if(mode==='add'&&!active){if(toAdd.has(perm)){toAdd.delete(perm);}else{toAdd.add(perm);}toRemove.delete(perm);}"
                + "if(mode==='remove'&&active){if(toRemove.has(perm)){toRemove.delete(perm);}else{toRemove.add(perm);}toAdd.delete(perm);}"
                + "markButtons();"
                + "}));"
                + "const form=document.getElementById('permissions-form');"
                + "if(form){form.addEventListener('submit',()=>{saveAdd.value=[...toAdd].join(',');saveRemove.value=[...toRemove].join(',');});}"
                + "if(roleNameInput){roleNameInput.addEventListener('input',refreshRolePreview);}"
                + "if(createRoleForm&&createRoleBtn){createRoleForm.addEventListener('submit',()=>{createRoleBtn.disabled=true;createRoleBtn.classList.add('opacity-60','cursor-not-allowed');createRoleBtn.textContent='Creating...';if(createRoleStatus){createRoleStatus.classList.remove('hidden');}});}"
                + "markButtons();"
                + "refreshRolePreview();"
                + "</script>";

        return HtmlTemplate.page("Permissions", "/permissions", content);
    }
}

