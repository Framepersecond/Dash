package dash.web;

import org.bukkit.Bukkit;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

public class FilesPage {

    private static final String[] ALLOWED_EXTENSIONS = { ".yml", ".yaml", ".properties", ".json", ".txt", ".log" };

    public static String render(String currentPath) {
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
            fileListHtml.append("<a href='/files?path=").append(parent).append(
                    "' class=\"flex items-center gap-3 p-3 rounded-xl hover:bg-white/10 transition-colors border border-transparent hover:border-white/10\">\n")
                    .append("<span class=\"material-symbols-outlined text-slate-400\">arrow_upward</span>\n")
                    .append("<span class=\"text-slate-300\">..</span>\n")
                    .append("</a>\n");
        }

        File[] files = targetDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory() && !file.getName().startsWith(".")) {
                    String path = currentPath == null || currentPath.isEmpty() ? file.getName()
                            : currentPath + "/" + file.getName();
                    fileListHtml.append("<a href='/files?path=").append(path).append(
                            "' class=\"flex items-center gap-3 p-3 rounded-xl hover:bg-white/10 transition-colors border border-transparent hover:border-white/10\">\n")
                            .append("<span class=\"material-symbols-outlined text-amber-400\">folder</span>\n")
                            .append("<span class=\"text-white\">").append(file.getName()).append("</span>\n")
                            .append("</a>\n");
                }
            }
            for (File file : files) {
                if (file.isFile() && isAllowedFile(file.getName())) {
                    String path = currentPath == null || currentPath.isEmpty() ? file.getName()
                            : currentPath + "/" + file.getName();
                    String icon = getFileIcon(file.getName());
                    long size = file.length();
                    String sizeStr = formatSize(size);

                    fileListHtml.append("<a href='/files/edit?path=").append(path).append(
                            "' class=\"flex items-center gap-3 p-3 rounded-xl hover:bg-white/10 transition-colors border border-transparent hover:border-white/10 group\">\n")
                            .append("<span class=\"material-symbols-outlined ").append(icon)
                            .append("\">description</span>\n")
                            .append("<div class=\"flex-1\">\n")
                            .append("<span class=\"text-white\">").append(file.getName()).append("</span>\n")
                            .append("<span class=\"text-slate-500 text-xs ml-2\">").append(sizeStr).append("</span>\n")
                            .append("</div>\n")
                            .append("<span class=\"material-symbols-outlined text-slate-500 opacity-0 group-hover:opacity-100 transition-opacity\">edit</span>\n")
                            .append("</a>\n");
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
                breadcrumb.append("<a href='/files?path=").append(pathBuilder)
                        .append("' class=\"text-primary hover:underline\">").append(part).append("</a>");
            }
        }

        String content = HtmlTemplate.statsHeader() +
                "<main class=\"flex-1 p-6 overflow-auto\">\n" +
                "<div class=\"rounded-3xl bg-glass-surface backdrop-blur-xl border border-glass-border overflow-hidden\">\n"
                +
                "<div class=\"flex items-center justify-between px-6 py-4 border-b border-white/5\">\n" +
                "<div class=\"flex items-center gap-3\">\n" +
                "<span class=\"material-symbols-outlined text-primary\">folder_open</span>\n" +
                "<h2 class=\"text-lg font-bold text-white\">File Manager</h2>\n" +
                "</div>\n" +
                "<div class=\"flex items-center gap-4\">\n" +
                "<div class=\"text-sm text-slate-400\">" + breadcrumb + "</div>\n" +
                "<label class=\"cursor-pointer flex items-center gap-2 px-3 py-1.5 rounded-lg bg-primary/20 text-primary hover:bg-primary hover:text-black transition-colors text-sm font-medium\">\n"
                +
                "<input type=\"file\" id=\"file-upload\" class=\"hidden\" multiple>\n" +
                "<span class=\"material-symbols-outlined text-[18px]\">upload_file</span>\n" +
                "<span>Files</span>\n" +
                "</label>\n" +
                "<label class=\"cursor-pointer flex items-center gap-2 px-3 py-1.5 rounded-lg bg-amber-500/20 text-amber-400 hover:bg-amber-500 hover:text-black transition-colors text-sm font-medium\">\n"
                +
                "<input type=\"file\" id=\"folder-upload\" class=\"hidden\" webkitdirectory directory multiple>\n" +
                "<span class=\"material-symbols-outlined text-[18px]\">folder_open</span>\n" +
                "<span>Folder</span>\n" +
                "</label>\n" +
                "</div>\n" +
                "</div>\n" +
                "<div class=\"p-4 grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-2\">\n" +
                fileListHtml.toString() +
                "</div>\n" +
                "</div>\n" +
                "</main>\n" +
                HtmlTemplate.statsScript() +
                "<script>\n" +
                "document.getElementById('file-upload').addEventListener('change', e => {\n" +
                "  const files = e.target.files;\n" +
                "  if (!files.length) return;\n" +
                "  uploadFiles(files, '');\n" +
                "});\n" +
                "document.getElementById('folder-upload').addEventListener('change', e => {\n" +
                "  const files = e.target.files;\n" +
                "  if (!files.length) return;\n" +
                "  uploadFiles(files, '');\n" +
                "});\n" +
                "async function uploadFiles(files, basePath) {\n" +
                "  const currentPath = '" + (currentPath == null ? "" : currentPath) + "';\n" +
                "  let successCount = 0;\n" +
                "  for (let file of files) {\n" +
                "    const formData = new FormData();\n" +
                "    formData.append('file', file);\n" +
                "    let relativePath = file.webkitRelativePath || file.name;\n" +
                "    let targetPath = currentPath ? currentPath + '/' + relativePath : relativePath;\n" +
                "    formData.append('fullpath', targetPath);\n" +
                "    try {\n" +
                "      const resp = await fetch('/api/upload/file', {method:'POST', body: formData});\n" +
                "      const d = await resp.json();\n" +
                "      if (d.success) successCount++;\n" +
                "    } catch(e) {}\n" +
                "  }\n" +
                "  alert('Uploaded ' + successCount + ' of ' + files.length + ' files');\n" +
                "  location.reload();\n" +
                "}\n" +
                "</script>\n";

        return HtmlTemplate.page("Files", "/files", content);
    }

    public static String renderEditor(String filePath) {
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

        if (!file.exists() || !isAllowedFile(file.getName())) {
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
                "<button id=\"save-btn\" class=\"flex items-center gap-2 px-4 py-2 rounded-lg bg-primary text-black font-semibold hover:bg-white transition-colors\">\n"
                +
                "<span class=\"material-symbols-outlined text-[18px]\">save</span>\n" +
                "<span>Save</span>\n" +
                "</button>\n" +
                "</div>\n" +
                "<div class=\"flex-1 relative\">\n" +
                "<textarea id=\"editor\" class=\"w-full h-full bg-black/50 text-emerald-400 font-mono text-sm p-6 resize-none focus:outline-none\" spellcheck=\"false\">"
                + content + "</textarea>\n" +
                "</div>\n" +
                "</div>\n" +
                "</main>\n" +
                HtmlTemplate.statsScript() +
                "<script>\n" +
                "document.getElementById('save-btn').addEventListener('click', () => {\n" +
                "  const content = document.getElementById('editor').value;\n" +
                "  fetch('/api/files/save', {\n" +
                "    method: 'POST',\n" +
                "    headers: {'Content-Type': 'application/x-www-form-urlencoded'},\n" +
                "    body: 'path=" + filePath + "&content=' + encodeURIComponent(content)\n" +
                "  }).then(r => r.json()).then(d => {\n" +
                "    if(d.success) alert('File saved successfully!');\n" +
                "    else alert('Error: ' + d.error);\n" +
                "  }).catch(e => alert('Error saving file'));\n" +
                "});\n" +
                "</script>\n";

        return HtmlTemplate.page("Edit: " + file.getName(), "/files", html);
    }

    private static boolean isAllowedFile(String name) {
        for (String ext : ALLOWED_EXTENSIONS) {
            if (name.toLowerCase().endsWith(ext))
                return true;
        }
        return false;
    }

    private static String getFileIcon(String name) {
        if (name.endsWith(".yml") || name.endsWith(".yaml"))
            return "text-blue-400";
        if (name.endsWith(".properties"))
            return "text-green-400";
        if (name.endsWith(".json"))
            return "text-amber-400";
        if (name.endsWith(".log"))
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
