package dash.web;

import org.bukkit.Bukkit;

import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;

public class FilesPage {

    private static final String[] EDITABLE_EXTENSIONS = {
            ".yml", ".yaml", ".properties", ".json", ".txt", ".log", ".conf", ".cfg", ".ini", ".toml",
            ".xml", ".mcmeta", ".md", ".csv", ".tsv", ".sh", ".bat", ".cmd", ".ps1", ".gradle",
            ".java", ".kt", ".kts", ".js", ".ts", ".html", ".css", ".sql"
    };

    public static String render(String currentPath) {
        boolean canWriteFiles = HtmlTemplate.can("dash.web.files.write");

        File serverDir = Bukkit.getWorldContainer();
        File targetDir = new File(serverDir, currentPath == null ? "" : currentPath);

        try {
            if (!targetDir.getCanonicalPath().startsWith(serverDir.getCanonicalPath())) {
                targetDir = serverDir;
                currentPath = "";
            }
        } catch (IOException e) {
            targetDir = serverDir;
            currentPath = "";
        }

        StringBuilder fileListHtml = new StringBuilder();

        if (currentPath != null && !currentPath.isEmpty()) {
            String parent = new File(currentPath).getParent();
            if (parent == null)
                parent = "";
            fileListHtml.append("<a href='/files?path=").append(url(parent)).append(
                    "' class=\"flex items-center gap-3 p-3 rounded-xl hover:bg-white/10 transition-colors border border-transparent hover:border-white/10\">\n")
                    .append("<span class=\"material-symbols-outlined text-slate-400\">arrow_upward</span>\n")
                    .append("<span class=\"text-slate-300\">..</span>\n")
                    .append("</a>\n");
        }

        File[] files = targetDir.listFiles();
        if (files != null) {
            Arrays.sort(files, Comparator
                    .comparing(File::isFile)
                    .thenComparing(f -> f.getName().toLowerCase()));

            int itemId = 0;
            for (File file : files) {
                if (file.isDirectory() && !file.getName().startsWith(".")) {
                    String path = currentPath == null || currentPath.isEmpty() ? file.getName()
                            : currentPath + "/" + file.getName();
                    String displayName = escapeHtml(file.getName());
                    itemId++;
                    fileListHtml.append("<div class=\"relative flex items-center gap-2 p-3 rounded-xl hover:bg-white/10 transition-colors border border-transparent hover:border-white/10\">\n")
                            .append("<a href='/files?path=").append(url(path)).append("' class=\"flex items-center gap-3 flex-1 min-w-0\">\n")
                            .append("<span class=\"material-symbols-outlined text-amber-400\">folder</span>\n")
                            .append("<span class=\"text-white truncate\">").append(displayName).append("</span>\n")
                            .append("</a>\n");
                    if (canWriteFiles) {
                        appendItemMenu(fileListHtml, itemId, path, file.getName(), true);
                    }
                    fileListHtml.append("</div>\n");
                }
            }
            for (File file : files) {
                if (file.isFile() && !file.getName().startsWith(".")) {
                    String path = currentPath == null || currentPath.isEmpty() ? file.getName()
                            : currentPath + "/" + file.getName();
                    String displayName = escapeHtml(file.getName());
                    String icon = getFileIcon(file.getName());
                    long size = file.length();
                    String sizeStr = formatSize(size);
                    boolean editable = isEditableFile(file.getName());
                    boolean isJar = file.getName().toLowerCase().endsWith(".jar");
                    itemId++;

                    if (editable) {
                        fileListHtml.append("<div class=\"relative flex items-center gap-2 p-3 rounded-xl hover:bg-white/10 transition-colors border border-transparent hover:border-white/10 group\">\n")
                                .append("<a href='/files/edit?path=").append(url(path)).append("' class=\"flex items-center gap-3 flex-1 min-w-0\">\n");
                    } else {
                        fileListHtml.append("<div class=\"relative flex items-center gap-2 p-3 rounded-xl bg-black/10 border border-white/5 group\" title=\"Binary/non-text file\">\n")
                                .append("<div class=\"flex items-center gap-3 flex-1 min-w-0\">\n");
                    }

                    fileListHtml
                            .append("<span class=\"material-symbols-outlined ").append(icon)
                            .append("\">description</span>\n")
                            .append("<div class=\"flex-1 min-w-0\">\n")
                            .append("<div class=\"flex items-center gap-2 min-w-0\">\n")
                            .append("<span class=\"text-white truncate\">").append(displayName).append("</span>\n")
                            .append("<span class=\"text-slate-500 text-xs whitespace-nowrap\">").append(sizeStr).append("</span>\n")
                            .append("</div>\n")
                            .append("</div>\n");

                    if (editable) {
                        fileListHtml.append("<span class=\"material-symbols-outlined text-slate-500 opacity-0 group-hover:opacity-100 transition-opacity\">edit</span>\n")
                                .append("</a>\n");
                    } else {
                        if (!isJar) {
                            fileListHtml.append("<span class=\"text-[11px] text-slate-500 uppercase tracking-wider\">Binary</span>\n");
                        }
                        fileListHtml.append("</div>\n");
                    }

                    if (canWriteFiles) {
                        appendItemMenu(fileListHtml, itemId, path, file.getName(), false);
                    }

                    fileListHtml.append("</div>\n");
                }
            }
        }

        if (fileListHtml.length() == 0) {
            fileListHtml.append("<p class=\"text-slate-500 text-center py-8\">No files or folders</p>\n");
        }

        StringBuilder breadcrumb = new StringBuilder();
        breadcrumb.append("<a href='/files' class=\"text-primary hover:underline\">Server</a>");
        if (currentPath != null && !currentPath.isEmpty()) {
            String[] parts = currentPath.split("/");
            StringBuilder pathBuilder = new StringBuilder();
            for (String part : parts) {
                if (pathBuilder.length() > 0)
                    pathBuilder.append("/");
                pathBuilder.append(part);
                breadcrumb.append(" <span class=\"text-slate-500\">/</span> ");
                breadcrumb.append("<a href='/files?path=").append(url(pathBuilder.toString()))
                        .append("' class=\"text-primary hover:underline\">").append(escapeHtml(part)).append("</a>");
            }
        }

        String uploadControls = canWriteFiles
                ? "<label class=\"cursor-pointer flex items-center gap-2 px-3 py-1.5 rounded-lg bg-primary/20 text-primary hover:bg-primary hover:text-black transition-colors text-sm font-medium\">\n"
                        +
                        "<input type=\"file\" id=\"file-upload\" class=\"hidden\" multiple>\n" +
                        "<span class=\"material-symbols-outlined text-[18px]\">upload_file</span>\n" +
                        "<span>Files</span>\n" +
                        "</label>\n" +
                        "<label class=\"cursor-pointer flex items-center gap-2 px-3 py-1.5 rounded-lg bg-amber-500/20 text-amber-400 hover:bg-amber-500 hover:text-black transition-colors text-sm font-medium\">\n"
                        +
                        "<input type=\"file\" id=\"folder-upload\" class=\"hidden\" webkitdirectory directory multiple>\n"
                        +
                        "<span class=\"material-symbols-outlined text-[18px]\">folder_open</span>\n" +
                        "<span>Folder</span>\n" +
                        "</label>\n"
                : "<label class=\"flex items-center gap-2 px-3 py-1.5 rounded-lg bg-slate-800 text-slate-500 text-sm font-medium cursor-not-allowed\">\n"
                        + "<span class=\"material-symbols-outlined text-[18px]\">upload_file</span>\n"
                        + "<span>Files</span>\n"
                        + "</label>\n"
                        + "<label class=\"flex items-center gap-2 px-3 py-1.5 rounded-lg bg-slate-800 text-slate-500 text-sm font-medium cursor-not-allowed\">\n"
                        + "<span class=\"material-symbols-outlined text-[18px]\">folder_open</span>\n"
                        + "<span>Folder</span>\n"
                        + "</label>\n";

        String uploadScript = canWriteFiles
                ? "document.getElementById('file-upload').addEventListener('change', e => {\n" +
                        "  const files = e.target.files;\n" +
                        "  if (!files.length) return;\n" +
                        "  uploadFiles(files, '');\n" +
                        "});\n" +
                        "document.getElementById('folder-upload').addEventListener('change', e => {\n" +
                        "  const files = e.target.files;\n" +
                        "  if (!files.length) return;\n" +
                        "  uploadFiles(files, '');\n" +
                        "});\n"
                : "";

        String content = HtmlTemplate.statsHeader() +
                "<main class=\"flex-1 min-w-0 p-6\">\n" +
                "<div class=\"rounded-3xl bg-glass-surface backdrop-blur-xl border border-glass-border overflow-hidden\">\n"
                +
                "<div class=\"flex items-center justify-between px-6 py-4 border-b border-white/5\">\n" +
                "<div class=\"flex items-center gap-3\">\n" +
                "<span class=\"material-symbols-outlined text-primary\">folder_open</span>\n" +
                "<h2 class=\"text-lg font-bold text-white\">File Manager</h2>\n" +
                "</div>\n" +
                "<div class=\"flex items-center gap-4\">\n" +
                "<div class=\"text-sm text-slate-400\">" + breadcrumb + "</div>\n" +
                uploadControls +
                "</div>\n" +
                "</div>\n" +
                "<div class=\"w-full overflow-x-auto pb-32 bg-gray-800 rounded-lg shadow ring-1 ring-gray-700\">\n" +
                "<div class=\"p-4 grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-2\">\n" +
                fileListHtml.toString() +
                "</div>\n" +
                "</div>\n" +
                "</div>\n" +
                "</main>\n" +
                HtmlTemplate.statsScript() +
                "<script>\n" +
                uploadScript +
                "function isProtectedUploadPath(path) {\n" +
                "  const p = (path || '').toLowerCase();\n" +
                "  const base = p.split('/').pop() || p;\n" +
                "  return base === 'session.lock' || base.endsWith('.lock') || base.endsWith('.lck') || base.endsWith('.pid');\n" +
                "}\n" +
                "async function uploadFiles(files, basePath) {\n" +
                "  const currentPath = '" + (currentPath == null ? "" : currentPath) + "';\n" +
                "  let successCount = 0;\n" +
                "  let skippedCount = 0;\n" +
                "  for (let file of files) {\n" +
                "    const formData = new FormData();\n" +
                "    formData.append('file', file);\n" +
                "    let relativePath = file.webkitRelativePath || file.name;\n" +
                "    let targetPath = currentPath ? currentPath + '/' + relativePath : relativePath;\n" +
                "    if (isProtectedUploadPath(targetPath)) { skippedCount++; continue; }\n" +
                "    formData.append('fullpath', targetPath);\n" +
                "    try {\n" +
                "      const resp = await fetch('/api/upload/file', {method:'POST', body: formData});\n" +
                "      const d = await resp.json();\n" +
                "      if (d.success) successCount++;\n" +
                "    } catch(e) {}\n" +
                "  }\n" +
                "  showToast('Uploaded ' + successCount + ' of ' + files.length + ' files' + (skippedCount ? (' (skipped ' + skippedCount + ' lock files)') : ''), successCount > 0 ? 'success' : 'error');\n" +
                "  if(window.dashNavigate){dashNavigate(location.pathname+location.search,false);}\n" +
                "}\n" +
                "function toggleItemMenu(id, event) {\n" +
                "  if (event) { event.preventDefault(); event.stopPropagation(); }\n" +
                "  document.querySelectorAll('[id^=file-item-menu-]').forEach(menu => {\n" +
                "    if (menu.id !== id) menu.classList.add('hidden');\n" +
                "  });\n" +
                "  const menu = document.getElementById(id);\n" +
                "  if (menu) menu.classList.toggle('hidden');\n" +
                "}\n" +
                "document.addEventListener('click', () => {\n" +
                "  document.querySelectorAll('[id^=file-item-menu-]').forEach(menu => menu.classList.add('hidden'));\n" +
                "});\n" +
                "function submitRename(formId, currentName) {\n" +
                "  const next = prompt('Rename to:', currentName || '');\n" +
                "  if (!next) return false;\n" +
                "  const trimmed = next.trim();\n" +
                "  if (!trimmed) return false;\n" +
                "  const form = document.getElementById(formId);\n" +
                "  if (!form) return false;\n" +
                "  const input = form.querySelector('input[name=new_name]');\n" +
                "  if (!input) return false;\n" +
                "  input.value = trimmed;\n" +
                "  return true;\n" +
                "}\n" +
                "</script>\n";

        return HtmlTemplate.page("Files", "/files", content);
    }

    private static void appendItemMenu(StringBuilder html, int itemId, String path, String name, boolean directory) {
        String menuId = "file-item-menu-" + itemId;
        String renameFormId = "file-rename-form-" + itemId;
        String escapedPath = escapeHtml(path);
        String escapedName = escapeHtml(name);
        String jsSafeName = escapeJs(name);
        String deletePrompt = directory ? "Delete folder and all contents?" : "Delete file?";

        html.append("<div class='relative'>")
                .append("<button type='button' class='h-8 w-8 rounded-lg flex items-center justify-center text-slate-400 hover:text-white hover:bg-white/10 transition-colors' onclick=\"toggleItemMenu('")
                .append(menuId)
                .append("', event)\">")
                .append("<span class='material-symbols-outlined text-[18px]'>more_vert</span>")
                .append("</button>")
                .append("<div id='").append(menuId)
                .append("' class='hidden absolute right-0 top-9 z-50 min-w-[170px] rounded-xl border border-white/10 bg-slate-900/95 backdrop-blur p-1.5 shadow-xl'>")
                .append("<form id='").append(renameFormId)
                .append("' action='/action' method='post' onsubmit=\"return submitRename('")
                .append(renameFormId)
                .append("','").append(jsSafeName).append("');\">")
                .append("<input type='hidden' name='action' value='file_rename'>")
                .append("<input type='hidden' name='path' value='").append(escapedPath).append("'>")
                .append("<input type='hidden' name='new_name' value=''>")
                .append("<button type='submit' class='w-full text-left px-3 py-2 rounded-lg text-sm text-slate-200 hover:bg-white/10'>Rename</button>")
                .append("</form>")
                .append("<form action='/action' method='post' onsubmit=\"return confirm('").append(deletePrompt).append("');\">")
                .append("<input type='hidden' name='action' value='file_delete'>")
                .append("<input type='hidden' name='path' value='").append(escapedPath).append("'>")
                .append("<button type='submit' class='w-full text-left px-3 py-2 rounded-lg text-sm text-rose-300 hover:bg-rose-500/15'>Delete</button>")
                .append("</form>")
                .append("</div>")
                .append("</div>");
    }

    private static String url(String raw) {
        return URLEncoder.encode(raw == null ? "" : raw, StandardCharsets.UTF_8);
    }

    private static String escapeHtml(String raw) {
        if (raw == null) {
            return "";
        }
        return raw
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static String escapeJs(String raw) {
        if (raw == null) {
            return "";
        }
        return raw
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "")
                .replace("\r", "");
    }

    public static String renderEditor(String filePath) {
        boolean canWriteFiles = HtmlTemplate.can("dash.web.files.write");

        File serverDir = Bukkit.getWorldContainer();
        File file = new File(serverDir, filePath);

        try {
            if (!file.getCanonicalPath().startsWith(serverDir.getCanonicalPath())) {
                return HtmlTemplate.page("Error", "/files",
                        "<main class='flex-1 p-6'><p class='text-rose-400'>Access denied</p></main>");
            }
        } catch (IOException e) {
            return HtmlTemplate.page("Error", "/files",
                    "<main class='flex-1 p-6'><p class='text-rose-400'>Access denied</p></main>");
        }

        if (!file.exists() || !isEditableFile(file.getName())) {
            return HtmlTemplate.page("Error", "/files",
                    "<main class='flex-1 p-6'><p class='text-rose-400'>File not found or not editable</p></main>");
        }

        String content;
        try {
            content = Files.readString(file.toPath());
        } catch (IOException e) {
            content = "Error reading file: " + e.getMessage();
        }

        content = content.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");

        String saveButton = canWriteFiles
                ? "<button id=\"save-btn\" class=\"flex items-center gap-2 px-4 py-2 rounded-lg bg-primary text-black font-semibold hover:bg-white transition-colors\">\n"
                        +
                        "<span class=\"material-symbols-outlined text-[18px]\">save</span>\n" +
                        "<span>Save</span>\n" +
                        "</button>\n"
                : "<button id=\"save-btn\" disabled class=\"flex items-center gap-2 px-4 py-2 rounded-lg bg-slate-700 text-slate-400 font-semibold cursor-not-allowed\">\n"
                        + "<span class=\"material-symbols-outlined text-[18px]\">save</span>\n"
                        + "<span>Save</span>\n"
                        + "</button>\n";

        String saveScript = canWriteFiles
                ? "document.getElementById('save-btn').addEventListener('click', () => {\n" +
                        "  const content = document.getElementById('editor').value;\n" +
                        "  fetch('/api/files/save', {\n" +
                        "    method: 'POST',\n" +
                        "    headers: {'Content-Type': 'application/x-www-form-urlencoded'},\n" +
                        "    body: 'path=" + filePath + "&content=' + encodeURIComponent(content)\n" +
                        "  }).then(r => r.json()).then(d => {\n" +
                        "    if(d.success) showToast('File saved successfully!', 'success');\n" +
                        "    else showToast('Error: ' + d.error, 'error');\n" +
                        "  }).catch(e => showToast('Error saving file', 'error'));\n" +
                        "});\n"
                : "";

        String html = HtmlTemplate.statsHeader() +
                "<main class=\"flex-1 p-6 flex flex-col overflow-hidden\">\n" +
                "<div class=\"rounded-3xl bg-glass-surface backdrop-blur-xl border border-glass-border overflow-hidden flex-1 flex flex-col\">\n"
                +
                "<div class=\"flex items-center justify-between px-6 py-4 border-b border-white/5\">\n" +
                "<div class=\"flex items-center gap-3\">\n" +
                "<a href='/files?path=" + new File(filePath).getParent()
                + "' class=\"h-8 w-8 rounded-lg flex items-center justify-center text-slate-400 hover:text-white hover:bg-white/10 transition-all\">\n"
                +
                "<span class=\"material-symbols-outlined\">arrow_back</span>\n" +
                "</a>\n" +
                "<span class=\"material-symbols-outlined text-primary\">edit_document</span>\n" +
                "<h2 class=\"text-lg font-bold text-white\">" + file.getName() + "</h2>\n" +
                "</div>\n" +
                saveButton +
                "</div>\n" +
                (canWriteFiles ? "" : "<p class='px-6 pt-3 text-xs text-slate-500'>Read-only access: editing is disabled.</p>\n") +
                "<div class=\"flex-1 relative\">\n" +
                "<textarea id=\"editor\"" + (canWriteFiles ? "" : " readonly") + " class=\"w-full h-full bg-black/50 text-emerald-400 font-mono text-sm p-6 resize-none focus:outline-none" + (canWriteFiles ? "" : " opacity-70 cursor-not-allowed") + "\" spellcheck=\"false\">"
                + content + "</textarea>\n" +
                "</div>\n" +
                "</div>\n" +
                "</main>\n" +
                HtmlTemplate.statsScript() +
                "<script>\n" +
                saveScript +
                "</script>\n";

        return HtmlTemplate.page("Edit: " + file.getName(), "/files", html);
    }

    private static boolean isEditableFile(String name) {
        String lower = name.toLowerCase();
        if (lower.equals("session.lock") || lower.endsWith(".lock") || lower.endsWith(".lck") || lower.endsWith(".pid")) {
            return false;
        }
        for (String ext : EDITABLE_EXTENSIONS) {
            if (name.toLowerCase().endsWith(ext))
                return true;
        }
        return false;
    }

    private static String getFileIcon(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".yml") || lower.endsWith(".yaml"))
            return "text-blue-400";
        if (lower.endsWith(".properties") || lower.endsWith(".cfg") || lower.endsWith(".conf"))
            return "text-green-400";
        if (lower.endsWith(".json") || lower.endsWith(".mcmeta"))
            return "text-amber-400";
        if (lower.endsWith(".jar"))
            return "text-purple-400";
        if (lower.endsWith(".zip") || lower.endsWith(".gz") || lower.endsWith(".tar") || lower.endsWith(".7z")
                || lower.endsWith(".rar"))
            return "text-cyan-400";
        if (lower.endsWith(".dat") || lower.endsWith(".mca"))
            return "text-rose-300";
        if (lower.endsWith(".log"))
            return "text-slate-400";
        return "text-slate-400";
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024)
            return bytes + " B";
        if (bytes < 1024 * 1024)
            return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
