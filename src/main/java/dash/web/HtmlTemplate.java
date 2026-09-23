package dash.web;

import dash.Dash;
import dash.FeatureFlags;
import dash.GithubUpdater;

import java.util.Set;

public class HtmlTemplate {

    private static final String[][] NAV_ITEMS = {
            { "home", "Dashboard", "/", "dash.web.stats.read" },
            { "neurology", "Intelligence", "/intelligence", "dash.web.intelligence.read" },
            { "terminal", "Console", "/console", "dash.web.console.read" },
            { "group", "Players", "/players", "dash.web.players.read" },
            { "folder", "Files", "/files", "dash.web.files.read" },
            { "extension", "Plugins", "/plugins", "dash.web.plugins.read" },
            { "travel_explore", "Plugin Browser", "/plugin-browser", "dash.web.plugins.read" },
            { "stethoscope", "Maintenance", "/maintenance", "dash.web.settings.read" },
            { "auto_awesome", "Dash AI", "/ai", "dash.web.ai.read" },
            { "support_agent", "Tickets", "/staff", "dash.web.stats.read" },
            { "campaign", "Notifications", "/notifications", "dash.web.pluginsettings.read" },
            { "query_stats", "Graphs", "/graphs", "dash.web.stats.read" },
            { "shield", "Guardian", "/guardian", "dash.web.guardian.read" },
            { "manage_accounts", "Users", "/users", "dash.web.users.manage" },
            { "menu_book", "Permissions", "/permissions", "dash.web.users.manage" },
            { "settings", "Settings", "/settings", "dash.web.settings.read" },
            { "receipt_long", "Audit Log", "/audit", "dash.web.audit.read" },
            { "schedule", "Tasks", "/scheduled-tasks", "dash.web.tasks.read" },
            { "system_update", "Updates", "/updates", "dash.web.settings.read" },
            { "tune", "Plugin Settings", "/plugin-settings", "dash.web.pluginsettings.read" }
    };

    private static final ThreadLocal<Set<String>> UI_PERMISSIONS = ThreadLocal.withInitial(Set::of);
    private static final ThreadLocal<Boolean> UI_BRIDGE_USER = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<String> UI_BRIDGE_MASTER_URL = ThreadLocal.withInitial(() -> "");
    private static final ThreadLocal<String> UI_LANGUAGE = ThreadLocal.withInitial(() -> I18n.DEFAULT_LANGUAGE);
    private static final ThreadLocal<String> UI_USER = ThreadLocal.withInitial(() -> "");
    private static final String BETA_FEATURES_CONFIG_KEY = "beta.enabled";

    public static void setUiPermissions(Set<String> permissions) {
        UI_PERMISSIONS.set(permissions == null ? Set.of() : permissions);
    }

    public static void clearUiPermissions() {
        UI_PERMISSIONS.remove();
    }

    public static void setBridgeContext(boolean bridgeUser, String bridgeMasterUrl) {
        UI_BRIDGE_USER.set(bridgeUser);
        UI_BRIDGE_MASTER_URL.set(bridgeMasterUrl == null ? "" : bridgeMasterUrl.trim());
    }

    public static void clearBridgeContext() {
        UI_BRIDGE_USER.remove();
        UI_BRIDGE_MASTER_URL.remove();
    }

    public static void setUiLanguage(String code) {
        UI_LANGUAGE.set(I18n.normalize(code));
    }

    public static void clearUiLanguage() {
        UI_LANGUAGE.remove();
    }

    public static String currentUiLanguage() {
        return UI_LANGUAGE.get();
    }

    public static void setUiUser(String username) {
        UI_USER.set(username == null ? "" : username.trim());
    }

    public static void clearUiUser() {
        UI_USER.remove();
    }

    public static String currentUiUser() {
        return UI_USER.get();
    }

    public static boolean can(String permission) {
        if (permission == null || permission.isBlank()) {
            return true;
        }
        Set<String> grants = UI_PERMISSIONS.get();
        if (grants.contains("*") || grants.contains("dash.web.*")) {
            return true;
        }
        if (grants.contains(permission)) {
            return true;
        }
        for (String grant : grants) {
            if (grant.endsWith(".*")) {
                String prefix = grant.substring(0, grant.length() - 1);
                if (permission.startsWith(prefix)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean betaFeaturesEnabled() { return FeatureFlags.betaEnabled(); }

    private static boolean isBetaNavItem(String path) {
        return "/maintenance".equals(path);
    }

    private static String intelligenceSubnav(boolean open) {
        String[][] tabs = {{"lab", "Investigate"}, {"change", "Changes"}, {"players", "Player care"},
                {"policy", "Policies"}, {"supply", "Software"}, {"reliability", "Reliability"}, {"response", "Incidents"}};
        StringBuilder html = new StringBuilder("<div class=\"dash-intelligence-subnav" + (open ? " is-open" : "")
                + "\" data-intelligence-subnav><div class=\"dash-intelligence-subnav-inner\">");
        for (String[] tab : tabs) html.append("<a href=\"/intelligence?tab=").append(tab[0])
                .append("\" data-intelligence-tab=\"").append(tab[0]).append("\"><span>").append(tab[1]).append("</span></a>");
        return html.append("</div></div>").toString();
    }

    private static String customizerEntry() {
        return "<a id=\"dash-sidebar-customizer\" class=\"dash-customizer-entry\" href=\"#customize-layout\" title=\"Hold to customize this page\" aria-label=\"Customize page layout\">"
                + "<span class=\"material-symbols-outlined text-[20px]\">dashboard_customize</span><span class=\"text-sm font-medium\">Customize layout</span><small>Hold</small></a>";
    }

    private static String sidebarCustomizationScript() {
        return """
                <style>
                .dash-sidebar-root-item[hidden],.dash-intelligence-subnav[hidden]{display:none!important}
                .dash-sidebar-editing .dash-sidebar-root-item{position:relative;animation:dashSidebarJiggle .24s ease-in-out infinite alternate;transform-origin:center;touch-action:none;user-select:none;cursor:grab;-webkit-user-drag:element}
                .dash-sidebar-editing .dash-sidebar-root-item.is-dragging{z-index:20;animation:none;cursor:grabbing;opacity:.78;box-shadow:0 10px 28px rgba(0,0,0,.34)}
                .dash-sidebar-editing .dash-sidebar-root-item:nth-of-type(2n){animation-direction:alternate-reverse;animation-delay:-.12s}
                .dash-sidebar-remove{position:absolute;top:-5px;right:-4px;z-index:15;display:none;width:22px;height:22px;place-items:center;border:2px solid #0f172a;border-radius:50%;background:#e2e8f0;color:#0f172a;font-size:15px;font-weight:900;line-height:1;box-shadow:0 4px 12px rgba(0,0,0,.38)}
                .dash-sidebar-editing .dash-sidebar-root-item .dash-sidebar-remove{display:grid}
                .dash-sidebar-sheet{position:fixed;inset:auto 0 0;z-index:80;display:none;border-top:1px solid rgba(100,116,139,.35);background:rgba(9,16,29,.97);box-shadow:0 -18px 50px rgba(0,0,0,.45);backdrop-filter:blur(18px)}
                .dash-sidebar-editing .dash-sidebar-sheet{display:block}.dash-sidebar-sheet-inner{width:min(100%,1120px);margin:auto;padding:14px 18px calc(14px + env(safe-area-inset-bottom))}
                .dash-sidebar-sheet-head{display:flex;align-items:center;justify-content:space-between;gap:12px}.dash-sidebar-sheet-head h2{font-size:14px;font-weight:800;color:#f8fafc}.dash-sidebar-sheet-head p{margin-top:2px;font-size:11px;color:#64748b}
                .dash-sidebar-done,.dash-sidebar-add{border:1px solid rgba(100,116,139,.32);border-radius:12px;background:rgba(30,41,59,.65);padding:8px 11px;color:#cbd5e1;font-size:11px;font-weight:800}.dash-sidebar-done{border-color:rgba(34,211,238,.4);background:rgba(8,145,178,.14);color:#a5f3fc}
                .dash-sidebar-library{display:flex;gap:8px;margin-top:11px;overflow-x:auto;padding:1px 1px 4px}.dash-sidebar-add{flex:0 0 auto}.dash-sidebar-empty{padding:8px 0;color:#64748b;font-size:11px}
                @keyframes dashSidebarJiggle{from{transform:rotate(-.45deg) translate3d(-.2px,0,0)}to{transform:rotate(.45deg) translate3d(.2px,0,0)}}
                @media(prefers-reduced-motion:reduce){.dash-sidebar-root-item{animation:none!important}.dash-sidebar-sheet,.dash-sidebar-add{transition:none!important}}
                </style>
                <script>
                (function(){
                  var reduce=window.matchMedia&&window.matchMedia('(prefers-reduced-motion: reduce)').matches;
                  var trigger=document.getElementById('dash-sidebar-customizer');if(!trigger||!trigger.parentElement)return;
                  var nav=trigger.parentElement,oldSheet=document.querySelector('.dash-sidebar-sheet');if(oldSheet)oldSheet.remove();
                  var raw=Array.prototype.slice.call(nav.children).filter(function(node){return node.tagName==='A'&&node.id!=='dash-sidebar-customizer';});
                  var items=raw.map(function(old){var fresh=old.cloneNode(true);old.replaceWith(fresh);return fresh;});
                  trigger=trigger.cloneNode(true);document.getElementById('dash-sidebar-customizer').replaceWith(trigger);
                  var key='dash.sidebar.hidden.v1',hidden=[];try{var saved=JSON.parse(localStorage.getItem(key)||'[]');if(Array.isArray(saved))hidden=saved;}catch(_){}
                  function id(item){return item.getAttribute('href')||'';}function label(item){var text=item.querySelector('span.text-sm');return (text?text.textContent:item.textContent).trim();}
                  function related(item){return id(item).split('?')[0]==='/intelligence'&&item.nextElementSibling&&item.nextElementSibling.matches('[data-intelligence-subnav]')?item.nextElementSibling:null;}
                  var orderKey='dash.sidebar.order.v1',order=[];try{var savedOrder=JSON.parse(localStorage.getItem(orderKey)||'[]');if(Array.isArray(savedOrder))order=savedOrder;}catch(_){}
                  items.sort(function(a,b){var ai=order.indexOf(id(a)),bi=order.indexOf(id(b));return (ai<0?9999:ai)-(bi<0?9999:bi);});items.forEach(function(item){var child=related(item);nav.insertBefore(item,trigger);if(child)nav.insertBefore(child,trigger);});
                  function orderedItems(){return Array.prototype.slice.call(nav.children).filter(function(node){return node.matches&&node.matches('a.dash-sidebar-root-item');});}
                  function saveOrder(){items=orderedItems();try{localStorage.setItem(orderKey,JSON.stringify(items.map(id)));}catch(_){}}
                  function moveItem(item,target,before){if(!item||!target||item===target)return;var child=related(item),targetChild=related(target),reference=before?target:(targetChild||target).nextSibling;nav.insertBefore(item,reference);if(child)nav.insertBefore(child,reference);saveOrder();}
                  function setHidden(item,value){item.hidden=value;var child=related(item);if(child)child.hidden=value;}
                  function save(){try{localStorage.setItem(key,JSON.stringify(hidden));}catch(_){}}
                  var sheet=document.createElement('section');sheet.className='dash-sidebar-sheet';sheet.setAttribute('aria-label','Available sidebar items');sheet.innerHTML='<div class="dash-sidebar-sheet-inner"><div class="dash-sidebar-sheet-head"><div><h2>Customize sidebar</h2><p>Add back navigation items hidden from the sidebar.</p></div><button type="button" class="dash-sidebar-done">Done</button></div><div class="dash-sidebar-library"></div></div>';document.body.appendChild(sheet);
                  var library=sheet.querySelector('.dash-sidebar-library');
                  function render(){var missing=items.filter(function(item){return item.hidden;});library.innerHTML='';if(!missing.length){library.innerHTML='<p class="dash-sidebar-empty">All sidebar items are visible.</p>';return;}missing.forEach(function(item){var add=document.createElement('button');add.type='button';add.className='dash-sidebar-add';add.textContent='+ '+label(item);add.onclick=function(){hidden=hidden.filter(function(value){return value!==id(item);});save();setHidden(item,false);render();if(!reduce&&item.animate)item.animate([{opacity:0,transform:'translateY(8px) scale(.96)'},{opacity:1,transform:'none'}],{duration:320,easing:'cubic-bezier(.16,1,.3,1)'});};library.appendChild(add);});}
                  function remove(item){if(item.hidden)return;if(hidden.indexOf(id(item))<0)hidden.push(id(item));save();var finished=false,done=function(){if(finished)return;finished=true;setHidden(item,true);item.style.removeProperty('opacity');item.style.removeProperty('transform');render();};if(reduce||!item.animate){done();return;}var animation=item.animate([{opacity:1,transform:'scale(1)'},{opacity:0,transform:'scale(.92)'}],{duration:180,easing:'ease-in'});animation.addEventListener('finish',done,{once:true});animation.addEventListener('cancel',done,{once:true});setTimeout(done,240);}
                  items.forEach(function(item){item.classList.add('dash-sidebar-root-item');setHidden(item,hidden.indexOf(id(item))>=0);var close=document.createElement('button');close.type='button';close.className='dash-sidebar-remove';close.setAttribute('aria-label','Remove '+label(item));close.textContent='×';function stopAndRemove(e){e.preventDefault();e.stopPropagation();if(e.stopImmediatePropagation)e.stopImmediatePropagation();remove(item);}close.addEventListener('pointerdown',function(e){e.stopPropagation();if(e.stopImmediatePropagation)e.stopImmediatePropagation();},{capture:true});close.addEventListener('click',stopAndRemove,{capture:true});item.appendChild(close);item.draggable=false;var timer,suppress=false,x=0,y=0,dragging=false;item.addEventListener('pointerdown',function(e){if(e.button!==0||e.target.closest('.dash-sidebar-remove'))return;x=e.clientX;y=e.clientY;var begin=function(){dragging=true;item.classList.add('is-dragging');try{item.setPointerCapture(e.pointerId);}catch(_){};};if(document.body.classList.contains('dash-sidebar-editing')){if(e.pointerType==='mouse')return;begin();e.preventDefault();return;}timer=setTimeout(function(){suppress=true;if(navigator.vibrate)navigator.vibrate(18);enter();begin();},540);});item.addEventListener('pointermove',function(e){if(dragging){var visible=orderedItems().filter(function(candidate){return candidate!==item&&!candidate.hidden;}),target=null,before=true;for(var i=0;i<visible.length;i++){var rect=visible[i].getBoundingClientRect();if(e.clientY<rect.bottom){target=visible[i];before=e.clientY<rect.top+rect.height/2;break;}}if(!target&&visible.length){target=visible[visible.length-1];before=false;}if(target)moveItem(item,target,before);e.preventDefault();return;}if(Math.abs(e.clientX-x)>8||Math.abs(e.clientY-y)>8)clearTimeout(timer);});function release(e){clearTimeout(timer);if(dragging){dragging=false;item.classList.remove('is-dragging');saveOrder();try{item.releasePointerCapture(e.pointerId);}catch(_){}}}item.addEventListener('pointerup',release);item.addEventListener('pointercancel',release);item.addEventListener('pointerleave',function(e){if(!dragging)release(e);});item.addEventListener('click',function(e){if(document.body.classList.contains('dash-sidebar-editing')||suppress){e.preventDefault();e.stopPropagation();suppress=false;}});});
                  var nativeDragged=null;items.forEach(function(item){item.addEventListener('dragstart',function(e){if(!document.body.classList.contains('dash-sidebar-editing')){e.preventDefault();return;}nativeDragged=item;item.classList.add('is-dragging');if(e.dataTransfer){e.dataTransfer.effectAllowed='move';e.dataTransfer.setData('text/plain',id(item));}});item.addEventListener('dragend',function(){item.classList.remove('is-dragging');nativeDragged=null;saveOrder();});});nav.addEventListener('dragover',function(e){if(!nativeDragged)return;e.preventDefault();if(e.dataTransfer)e.dataTransfer.dropEffect='move';var visible=orderedItems().filter(function(candidate){return candidate!==nativeDragged&&!candidate.hidden;}),target=null,before=true;for(var i=0;i<visible.length;i++){var rect=visible[i].getBoundingClientRect();if(e.clientY<rect.bottom){target=visible[i];before=e.clientY<rect.top+rect.height/2;break;}}if(!target&&visible.length){target=visible[visible.length-1];before=false;}if(target)moveItem(nativeDragged,target,before);});nav.addEventListener('drop',function(e){if(nativeDragged){e.preventDefault();saveOrder();}});
                  function enter(){document.body.classList.add('dash-sidebar-editing');items.forEach(function(item){item.draggable=true;});render();sheet.querySelector('.dash-sidebar-done').focus();}function leave(){document.body.classList.remove('dash-sidebar-editing');items.forEach(function(item){item.draggable=false;item.classList.remove('is-dragging');});nativeDragged=null;trigger.focus();}
                  var holdTimer=0,suppressTrigger=false;trigger.onclick=function(e){e.preventDefault();if(suppressTrigger){suppressTrigger=false;return;}enter();};trigger.onpointerdown=function(e){if(e.button!==0)return;trigger.classList.add('is-holding');holdTimer=setTimeout(function(){suppressTrigger=true;trigger.classList.remove('is-holding');if(navigator.vibrate)navigator.vibrate(18);enter();},520);};function cancel(){clearTimeout(holdTimer);trigger.classList.remove('is-holding');}trigger.onpointerup=cancel;trigger.onpointercancel=cancel;trigger.onpointerleave=cancel;
                  sheet.querySelector('.dash-sidebar-done').onclick=leave;document.addEventListener('keydown',function(e){if(e.key==='Escape'&&document.body.classList.contains('dash-sidebar-editing'))leave();});
                })();
                </script>
                """;
    }



    public static String head(String title) {
        String lang = UI_LANGUAGE.get();
        if (lang == null || lang.isBlank()) lang = I18n.DEFAULT_LANGUAGE;
        return "<!DOCTYPE html>\n" +
                "<html class=\"dark\" lang=\"" + lang + "\"><head>\n" +
                "<meta charset=\"utf-8\"/>\n" +
                "<meta content=\"width=device-width, initial-scale=1.0, maximum-scale=1.0\" name=\"viewport\"/>\n" +
                "<title>" + title + " - Dash Admin</title>\n" +
                "<link href=\"https://fonts.googleapis.com/css2?family=Material+Symbols+Outlined:opsz,wght,FILL,GRAD@20..48,100..700,0..1,-50..200&display=swap\" rel=\"stylesheet\"/>\n"
                +
                "<link href=\"https://fonts.googleapis.com/css2?family=Outfit:wght@400;500;600;700;800&family=Plus+Jakarta+Sans:wght@300;400;500;600;700&family=JetBrains+Mono:wght@400;500&display=swap\" rel=\"stylesheet\"/>\n"
                +
                "<link href=\"/assets/dash-4.3.css\" rel=\"stylesheet\"/>\n" +
                "<script src=\"https://cdn.jsdelivr.net/npm/chart.js\"></script>\n" +
                "<script src=\"https://cdn.jsdelivr.net/npm/skinview3d@3.4.1/bundles/skinview3d.bundle.js\" onerror=\"this.onerror=null;this.src='https://unpkg.com/skinview3d@3.4.1/bundles/skinview3d.bundle.js'\"></script>\n" +
                "<style>\n" +
                ".material-symbols-outlined {\n" +
                "  display: inline-block !important;\n" +
                "  width: 1em !important;\n" +
                "  height: 1em !important;\n" +
                "  overflow: hidden !important;\n" +
                "  white-space: nowrap !important;\n" +
                "  word-wrap: normal !important;\n" +
                "  direction: ltr !important;\n" +
                "}\n" +
                "@keyframes m3Indeterminate {\n" +
                "  0% { left: -35%; right: 100%; }\n" +
                "  60% { left: 100%; right: -90%; }\n" +
                "  100% { left: 100%; right: -90%; }\n" +
                "}\n" +
                "@keyframes m3IndeterminateShort {\n" +
                "  0% { left: -200%; right: 100%; }\n" +
                "  60% { left: 107%; right: -8%; }\n" +
                "  100% { left: 107%; right: -8%; }\n" +
                "}\n" +
                ".m3-progress-bar-1 {\n" +
                "  position: absolute;\n" +
                "  background-color: #22d3ee;\n" +
                "  top: 0;\n" +
                "  bottom: 0;\n" +
                "  will-change: left, right;\n" +
                "  animation: m3Indeterminate 2.1s cubic-bezier(0.65, 0.815, 0.735, 0.395) infinite;\n" +
                "}\n" +
                ".m3-progress-bar-2 {\n" +
                "  position: absolute;\n" +
                "  background-color: #22d3ee;\n" +
                "  top: 0;\n" +
                "  bottom: 0;\n" +
                "  will-change: left, right;\n" +
                "  animation: m3IndeterminateShort 2.1s cubic-bezier(0.165, 0.84, 0.44, 1) infinite;\n" +
                "  animation-delay: 1.15s;\n" +
                "}\n" +
                "* { box-sizing: border-box; }\n" +
                "body { margin: 0; padding: 0; font-family: sans-serif; overflow-x: hidden; }\n" +
                "input[type=\"text\"], input[type=\"password\"], input[type=\"number\"], select {\n" +
                "  background-color: rgba(9, 13, 22, 0.45) !important;\n" +
                "  border: 1px solid rgba(255, 255, 255, 0.1) !important;\n" +
                "  border-radius: 9999px !important;\n" +
                "  padding: 0.625rem 1.25rem !important;\n" +
                "  font-size: 0.8125rem !important;\n" +
                "  font-family: 'Plus Jakarta Sans', sans-serif !important;\n" +
                "  color: #ffffff !important;\n" +
                "  transition: background-color .22s cubic-bezier(.22,1,.36,1), border-color .22s cubic-bezier(.22,1,.36,1), transform .22s cubic-bezier(.22,1,.36,1) !important;\n" +
                "  outline: none !important;\n" +
                "}\n" +
                "input[type=\"text\"]:focus, input[type=\"password\"]:focus, input[type=\"number\"]:focus, select:focus {\n" +
                "  border-color: #22d3ee !important;\n" +
                "  box-shadow: 0 0 0 3px rgba(34, 211, 238, 0.15) !important;\n" +
                "  background-color: rgba(9, 13, 22, 0.7) !important;\n" +
                "}\n" +
                "textarea {\n" +
                "  background-color: rgba(9, 13, 22, 0.45) !important;\n" +
                "  border: 1px solid rgba(255, 255, 255, 0.1) !important;\n" +
                "  border-radius: 1.25rem !important;\n" +
                "  padding: 0.75rem 1.25rem !important;\n" +
                "  font-size: 0.8125rem !important;\n" +
                "  font-family: 'Plus Jakarta Sans', sans-serif !important;\n" +
                "  color: #ffffff !important;\n" +
                "  transition: background-color .22s cubic-bezier(.22,1,.36,1), border-color .22s cubic-bezier(.22,1,.36,1), transform .22s cubic-bezier(.22,1,.36,1) !important;\n" +
                "  outline: none !important;\n" +
                "}\n" +
                "textarea:focus {\n" +
                "  border-color: #22d3ee !important;\n" +
                "  box-shadow: 0 0 0 3px rgba(34, 211, 238, 0.15) !important;\n" +
                "  background-color: rgba(9, 13, 22, 0.7) !important;\n" +
                "}\n" +
                ".app-shell { display: flex; min-height: 100vh; width: 100%; }\n" +
                ".sidebar { flex-shrink: 0; width: 260px; }\n" +
                ".content { flex-grow: 1; min-width: 0; background: #0f172a; }\n" +
                ".page-shell { width: 95%; max-width: 1200px; margin: 0 auto; }\n" +
                ".sidebar-overlay { display: none; }\n" +
                ".dash-sidebar{max-height:100vh;max-height:100dvh;overflow-y:auto;overscroll-behavior:contain;scrollbar-gutter:stable}\n" +
                ".dash-mobile-top{width:100%;min-height:56px;flex:0 0 auto;overflow:hidden}\n" +
                "#mobile-menu-toggle{width:2.25rem!important;min-width:2.25rem!important;max-width:2.25rem!important;flex:0 0 2.25rem!important;align-self:center}\n" +
                ".dash-table-wrap{max-width:100%;overflow-x:auto;-webkit-overflow-scrolling:touch}\n" +
                ".console-scrollbar::-webkit-scrollbar { width: 6px; }\n" +
                ".console-scrollbar::-webkit-scrollbar-track { background: transparent; }\n" +
                ".console-scrollbar::-webkit-scrollbar-thumb { background: rgba(255,255,255,0.1); border-radius: 9999px; }\n" +
                ".console-scrollbar::-webkit-scrollbar-thumb:hover { background: rgba(255,255,255,0.2); }\n" +
                ".pixelated { image-rendering: pixelated; image-rendering: crisp-edges; }\n" +
                ":root{--dash-expressive:cubic-bezier(.22,1,.36,1);--dash-emphasized:cubic-bezier(.16,1,.3,1);--dash-standard:cubic-bezier(.2,.8,.2,1);--dash-quick:cubic-bezier(.2,0,0,1);--dash-spring:cubic-bezier(.34,1.56,.64,1)}\n" +
                "html{scroll-behavior:smooth}\n" +
                "body{background-color:#090d16;color-scheme:dark}\n" +
                "#dash-content{height:100vh;height:100dvh;scroll-behavior:auto;overscroll-behavior:contain;overflow-y:auto;overflow-x:hidden;-webkit-overflow-scrolling:touch;touch-action:pan-y}\n" +
                "#dash-content>main,main{transform-origin:top center;backface-visibility:hidden;transform:translateZ(0)}\n" +
                "button,a[href],input,select,textarea{-webkit-tap-highlight-color:transparent}\n" +
                "button:not([disabled]),a[href],[role=\"button\"]{touch-action:manipulation}\n" +
                "button:not([disabled]),a[href],[role=\"button\"],input,select,textarea{transition-property:transform,opacity,background-color,border-color,color,box-shadow;transition-duration:.24s;transition-timing-function:var(--dash-expressive)}\n" +
                "button:not([disabled]){position:relative;overflow:hidden;isolation:isolate}\n" +
                "button:not([disabled])::after,.dash-sidebar a[href]::after{content:\"\";position:absolute;left:var(--dash-ripple-x,50%);top:var(--dash-ripple-y,50%);width:10px;height:10px;border-radius:9999px;background:rgba(255,255,255,.18);opacity:0;transform:translate(-50%,-50%) scale(1);pointer-events:none;z-index:0}\n" +
                "button:not([disabled])>*,.dash-sidebar a[href]>*{position:relative;z-index:1}\n" +
                "button:not([disabled]).dash-ripple::after,.dash-sidebar a[href].dash-ripple::after{animation:dashRipple .48s var(--dash-emphasized)}\n" +
                ".material-symbols-outlined{transition:transform .28s var(--dash-expressive),color .22s var(--dash-standard),opacity .22s var(--dash-standard);transform-origin:center}\n" +
                "button:not([disabled]):hover .material-symbols-outlined,a[href]:hover .material-symbols-outlined{transform:translateY(-1px) scale(1.08)}\n" +
                "button:not([disabled]):hover{transform:translate3d(0,-1px,0) scale(1.006)}\n" +
                "input[type=\"text\"],input[type=\"password\"],input[type=\"number\"],select,textarea{transition-property:background-color,border-color,box-shadow,transform!important;transition-duration:.24s!important;transition-timing-function:var(--dash-expressive)!important}\n" +
                "input[type=\"text\"]:focus,input[type=\"password\"]:focus,input[type=\"number\"]:focus,select:focus,textarea:focus{transform:translateY(-1px)}\n" +
                "select.dash-select-native{position:absolute!important;opacity:0!important;pointer-events:none!important;width:1px!important;min-width:1px!important;height:1px!important;padding:0!important;border:0!important}\n" +
                ".dash-select{position:relative;width:100%;min-width:0}\n" +
                ".dash-select-button{width:100%;min-height:42px;display:flex;align-items:center;justify-content:space-between;gap:.75rem;border:1px solid rgba(148,163,184,.32);border-radius:1rem;background:rgba(15,23,42,.82);color:#f8fafc;padding:.68rem 1rem;text-align:left;font-size:.875rem;line-height:1.25rem;box-shadow:inset 0 1px 0 rgba(255,255,255,.04);transition:transform .22s var(--dash-expressive),border-color .2s var(--dash-standard),background-color .2s var(--dash-standard),box-shadow .22s var(--dash-standard)}\n" +
                ".dash-select-button:hover{border-color:rgba(34,211,238,.48);background:rgba(15,23,42,.96)}\n" +
                ".dash-select-button:focus-visible,.dash-select[data-open=\"true\"] .dash-select-button{outline:none;border-color:#22d3ee;box-shadow:0 0 0 3px rgba(34,211,238,.16),inset 0 1px 0 rgba(255,255,255,.05)}\n" +
                ".dash-select-value{min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}\n" +
                ".dash-select-chevron{flex:none;color:#67e8f9;transition:transform .24s var(--dash-expressive),opacity .2s var(--dash-standard)}\n" +
                ".dash-select[data-open=\"true\"] .dash-select-chevron{transform:rotate(180deg)}\n" +
                ".dash-select-menu{position:fixed;left:0;top:0;right:auto;z-index:9998;min-width:12rem;max-width:calc(100vw - 24px);max-height:260px;overflow-y:auto;border:1px solid rgba(148,163,184,.26);border-radius:1rem;background:rgba(15,23,42,.98);box-shadow:0 22px 46px -22px rgba(0,0,0,.85),0 0 0 1px rgba(255,255,255,.03);padding:.35rem;opacity:0;transform:translate3d(0,-6px,0) scale(.985);transform-origin:top center;pointer-events:none;will-change:transform,opacity;transition:opacity .16s var(--dash-standard),transform .22s var(--dash-expressive)}\n" +
                ".dash-select-menu[data-placement=\"top\"]{transform:translate3d(0,6px,0) scale(.985);transform-origin:bottom center}\n" +
                ".dash-select-menu[data-open=\"true\"]{opacity:1;transform:translate3d(0,0,0) scale(1);pointer-events:auto}\n" +
                ".dash-select-option{width:100%;display:flex;align-items:center;justify-content:space-between;gap:.75rem;border:0;border-radius:.75rem;background:transparent;color:#cbd5e1;padding:.62rem .75rem;text-align:left;font-size:.875rem;line-height:1.2rem;cursor:pointer;transition:background-color .14s var(--dash-standard),color .14s var(--dash-standard),transform .18s var(--dash-expressive)}\n" +
                ".dash-select-option:hover,.dash-select-option[data-active=\"true\"]{background:rgba(34,211,238,.12);color:#f8fafc;transform:translate3d(2px,0,0)}\n" +
                ".dash-select-option[aria-selected=\"true\"]{background:rgba(34,211,238,.18);color:#67e8f9;font-weight:700}\n" +
                ".dash-select-option:disabled{opacity:.45;cursor:not-allowed;transform:none}\n" +
                ".dash-select.is-disabled{opacity:.6;pointer-events:none}\n" +
                "tbody tr{transition:transform .22s var(--dash-expressive),background-color .18s var(--dash-standard),border-color .18s var(--dash-standard)}\n" +
                "tbody tr:hover{transform:translate3d(2px,0,0)}\n" +
                "@media (max-width: 768px) {\n" +
                "  .sidebar { position: fixed; top: 0; left: 0; height: 100vh; height: 100dvh; z-index: 70 !important; transform: translate3d(-100%,0,0); transition: transform 0.34s var(--dash-expressive); display: flex; will-change:transform; }\n" +
                "  body.sidebar-open .sidebar { transform: translateX(0); }\n" +
                "  body.sidebar-open { overflow: hidden; }\n" +
                "  .sidebar-overlay { display: block; position: fixed; inset: 0; background: rgba(2, 6, 23, 0.6); opacity: 0; pointer-events: none; transition: opacity 0.22s var(--dash-standard); z-index: 60 !important; }\n" +
                "  body.sidebar-open .sidebar-overlay { opacity: 1; pointer-events: auto; }\n" +
                "  .content { width: 100%; }\n" +
                "  body{height:100dvh;min-height:100dvh;overflow:hidden}\n" +
                "  #dash-content{height:100dvh!important;min-height:0!important;overflow-y:auto!important;overflow-x:hidden!important;padding-bottom:6rem;touch-action:pan-y;-webkit-overflow-scrolling:touch}\n" +
                "  #dash-content>main{min-height:max-content}\n" +
                "  main{width:100%;max-width:100%;padding-left:1rem!important;padding-right:1rem!important}\n" +
                "  .page-shell{width:100%;max-width:100%;padding-left:.75rem;padding-right:.75rem}\n" +
                "  .dash-sidebar{width:min(86vw,320px)!important;padding-bottom:calc(1rem + env(safe-area-inset-bottom))}\n" +
                "  .dash-sidebar .mt-auto{margin-top:1rem!important}\n" +
                "  table{display:block;max-width:100%;min-width:min(720px,calc(100vw - 2rem));overflow-x:auto;white-space:nowrap;-webkit-overflow-scrolling:touch}\n" +
                "  form{max-width:100%}\n" +
                "  input,select,textarea{max-width:100%}\n" +
                "}\n" +
                "@keyframes m3SpringToastIn{0%{opacity:0;transform:translate3d(28px,-4px,0) scale(.97)}70%{transform:translate3d(-2px,0,0) scale(1.01)}100%{opacity:1;transform:translate3d(0,0,0) scale(1)}}\n" +
                "@keyframes m3SpringToastOut{0%{opacity:1;transform:translate3d(0,0,0) scale(1)}100%{opacity:0;transform:translate3d(28px,-2px,0) scale(.97)}}\n" +
                ".dash-toast-in{animation:m3SpringToastIn .34s var(--dash-expressive) forwards}\n" +
                ".dash-toast-out{animation:m3SpringToastOut .18s cubic-bezier(.4,0,1,1) forwards}\n" +
                "@keyframes dashCardIn{from{opacity:0;transform:translate3d(0,10px,0) scale(.992)}to{opacity:1;transform:translate3d(0,0,0) scale(1)}}\n" +
                "@keyframes dashNavIn{from{opacity:0;transform:translate3d(-10px,0,0) scale(.985)}to{opacity:1;transform:translate3d(0,0,0) scale(1)}}\n" +
                "@keyframes dashSpringPop{0%{opacity:0;transform:translate3d(0,6px,0) scale(.96)}100%{opacity:1;transform:translate3d(0,0,0) scale(1)}}\n" +
                "@keyframes dashSurfaceOut{to{opacity:.62;transform:translate3d(0,-5px,0) scale(.996)}}\n" +
                "@keyframes dashRipple{0%{opacity:.2;transform:translate(-50%,-50%) scale(1)}100%{opacity:0;transform:translate(-50%,-50%) scale(24)}}\n" +
                "@keyframes dashValueFlash{0%{transform:translate3d(0,-1px,0) scale(1.035);text-shadow:0 0 14px rgba(34,211,238,.28)}55%{transform:translate3d(0,0,0) scale(1.008);text-shadow:0 0 8px rgba(34,211,238,.18)}100%{transform:translate3d(0,0,0) scale(1);text-shadow:none}}\n" +
                "@keyframes dashStatusFlip{0%{opacity:.7;transform:translate3d(0,3px,0) scale(.96)}62%{opacity:1;transform:translate3d(0,-1px,0) scale(1.03)}100%{opacity:1;transform:translate3d(0,0,0) scale(1)}}\n" +
                "@keyframes dashRowIn{from{opacity:0;transform:translate3d(7px,5px,0) scale(.996)}to{opacity:1;transform:translate3d(0,0,0) scale(1)}}\n" +
                ".dash-card-in{animation:dashCardIn .38s var(--dash-emphasized) both}\n" +
                ".dash-page-out{animation:dashSurfaceOut .12s cubic-bezier(.4,0,1,1) both;pointer-events:none;will-change:opacity,transform}\n" +
                ".dash-page-in{animation:dashCardIn .38s var(--dash-emphasized) both;will-change:opacity,transform}\n" +
                ".dash-nav-in{animation:dashNavIn .3s var(--dash-expressive) both}\n" +
                ".dash-spring-pop{animation:dashSpringPop .3s var(--dash-expressive) both}\n" +
                ".dash-value-flash{display:inline-block;animation:dashValueFlash .46s var(--dash-spring) both;will-change:transform,text-shadow}\n" +
                ".dash-status-flip{display:inline-block;animation:dashStatusFlip .34s var(--dash-spring) both;will-change:transform,opacity}\n" +
                ".dash-row-in{animation:dashRowIn .3s var(--dash-emphasized) both;will-change:transform,opacity}\n" +
                ".dash-sidebar a[href]{position:relative;overflow:hidden;isolation:isolate;transition:transform .28s var(--dash-expressive),background-color .2s var(--dash-standard),border-color .2s var(--dash-standard),color .2s var(--dash-standard),box-shadow .24s var(--dash-standard)}\n" +
                ".dash-sidebar a[href]::before{content:\"\";position:absolute;left:10px;top:50%;width:4px;height:40%;min-height:18px;border-radius:9999px;background:currentColor;opacity:0;transform:translate3d(-8px,-50%,0) scaleY(.6);transition:opacity .22s var(--dash-standard),transform .28s var(--dash-expressive);pointer-events:none}\n" +
                ".dash-sidebar a[href]:hover{transform:translate3d(4px,0,0) scale(1.006)}\n" +
                ".dash-sidebar a[href]:hover::before{opacity:.5;transform:translate3d(-2px,-50%,0) scaleY(.85)}\n" +
                ".dash-sidebar a[href].text-primary::before,.dash-sidebar a[href].font-semibold::before{opacity:.95;transform:translate3d(-2px,-50%,0) scaleY(1)}\n" +
                ".dash-hover-lift{transition:transform .3s var(--dash-expressive),box-shadow .3s var(--dash-standard),border-color .2s var(--dash-standard),background-color .2s var(--dash-standard);backface-visibility:hidden}\n" +
                ".dash-hover-lift:hover{transform:translate3d(0,-3px,0) scale(1.006);box-shadow:0 14px 34px -18px rgba(0,0,0,.7),0 0 20px -8px rgba(34,211,238,.2)}\n" +
                "#dash-progress-bar{transition:opacity .16s var(--dash-standard)!important}\n" +
                "html[data-dash-navigating=\"true\"] #dash-content,html[data-dash-navigating=\"true\"] .dash-sidebar,html[data-dash-mutation-pending=\"true\"] #dash-content,html[data-dash-mutation-pending=\"true\"] .dash-sidebar{pointer-events:none;cursor:progress}\n" +
                "#dash-progress-bar .m3-progress-bar-1,#dash-progress-bar .m3-progress-bar-2{transform:translateZ(0)}\n" +
                motionLayerStyles() +
                "@media (prefers-reduced-motion: reduce){*,*::before,*::after{animation-duration:.01ms!important;animation-iteration-count:1!important;scroll-behavior:auto!important;transition-duration:.01ms!important}.dash-page-out,.dash-page-in{animation:none!important;transform:none!important}}\n" +
                "</style>\n" +
                "<script>\n" +
                "window.dashBump=function(el,cls){\n" +
                "  if(!el)return;cls=cls||'dash-value-flash';\n" +
                "  el.classList.remove(cls);void el.offsetWidth;el.classList.add(cls);\n" +
                "  setTimeout(function(){el.classList.remove(cls);},620);\n" +
                "};\n" +
                "window.dashSetText=function(el,value,cls){\n" +
                "  if(!el)return;var next=String(value);\n" +
                "  if(el.textContent!==next){el.textContent=next;window.dashBump(el,cls||'dash-value-flash');}\n" +
                "};\n" +
                "window.showToast=function(msg,type){\n" +
                "  var host=document.getElementById('dash-toast-host');\n" +
                "  if(!host){host=document.createElement('div');host.id='dash-toast-host';\n" +
                "  host.className='fixed top-4 right-4 z-[100] flex flex-col gap-2 pointer-events-none';\n" +
                "  document.body.appendChild(host);}\n" +
                "  var t=document.createElement('div'),ok=type==='success';\n" +
                "  t.className='pointer-events-auto min-w-[220px] max-w-sm rounded-xl border px-4 py-3 text-sm font-medium shadow-2xl backdrop-blur-xl dash-toast-in '+(ok?'bg-emerald-500/20 text-emerald-300 border-emerald-500/30':'bg-rose-500/20 text-rose-300 border-rose-500/30');\n" +
                "  t.textContent=msg;\n" +
                "  host.appendChild(t);\n" +
                "  setTimeout(function(){t.classList.remove('dash-toast-in');t.classList.add('dash-toast-out');},3000);\n" +
                "  setTimeout(function(){t.remove();},3300);\n" +
                "};\n" +
                "window.dashInitCustomSelects=function(root){\n" +
                "  root=root||document;\n" +
                "  document.querySelectorAll('.dash-select-menu').forEach(function(menu){var owner=menu._dashSelectOwner;if(!owner||!document.documentElement.contains(owner)){menu.remove();}});\n" +
                "  function closeOne(w){if(!w)return;w.removeAttribute('data-open');var b=w.querySelector('.dash-select-button');if(b)b.setAttribute('aria-expanded','false');if(w._dashSelectMenu)w._dashSelectMenu.removeAttribute('data-open');}\n" +
                "  function closeAll(except){document.querySelectorAll('.dash-select[data-open=\"true\"]').forEach(function(w){if(w!==except)closeOne(w);});document.querySelectorAll('.dash-select-menu[data-open=\"true\"]').forEach(function(m){var owner=m._dashSelectOwner;if(!owner||(owner!==except&&(!document.documentElement.contains(owner)||owner.getAttribute('data-open')!=='true')))m.removeAttribute('data-open');});}\n" +
                "  function positionMenu(w){var b=w&&w.querySelector?w.querySelector('.dash-select-button'):null,m=w&&w._dashSelectMenu;if(!b||!m)return;var r=b.getBoundingClientRect(),gap=7,vw=document.documentElement.clientWidth||window.innerWidth,vh=document.documentElement.clientHeight||window.innerHeight;var width=Math.max(160,Math.min(r.width,vw-16));var left=Math.max(8,Math.min(r.left,vw-width-8));var below=vh-r.bottom-gap,above=r.top-gap;var openUp=below<190&&above>below;var room=(openUp?above:below)-8;if(room<128&&above>below){openUp=true;room=above-8;}var maxH=Math.max(128,Math.min(260,room));var top=openUp?Math.max(8,r.top-gap-maxH):Math.min(vh-8,r.bottom+gap);m.style.left=Math.round(left)+'px';m.style.top=Math.round(top)+'px';m.style.width=Math.round(width)+'px';m.style.maxHeight=Math.round(maxH)+'px';m.dataset.placement=openUp?'top':'bottom';}\n" +
                "  function openSelect(w){closeAll(w);w.setAttribute('data-open','true');var b=w.querySelector('.dash-select-button');if(b)b.setAttribute('aria-expanded','true');positionMenu(w);if(w._dashSelectMenu)w._dashSelectMenu.setAttribute('data-open','true');}\n" +
                "  function positionOpenMenus(){document.querySelectorAll('.dash-select[data-open=\"true\"]').forEach(positionMenu);}\n" +
                "  root.querySelectorAll('select:not([data-dash-select-ready])').forEach(function(sel){\n" +
                "    if(sel.multiple||sel.closest('.dash-select'))return;sel.dataset.dashSelectReady='1';\n" +
                "    var wrap=document.createElement('div');wrap.className='dash-select';if((sel.className||'').indexOf('flex-1')>=0){wrap.style.flex='1 1 0%';}\n" +
                "    var btn=document.createElement('button');btn.type='button';btn.className='dash-select-button';btn.setAttribute('aria-haspopup','listbox');btn.setAttribute('aria-expanded','false');\n" +
                "    var value=document.createElement('span');value.className='dash-select-value';var icon=document.createElement('span');icon.className='material-symbols-outlined dash-select-chevron text-[20px]';icon.textContent='expand_more';\n" +
                "    var menu=document.createElement('div');menu.className='dash-select-menu console-scrollbar';menu.setAttribute('role','listbox');menu.addEventListener('click',function(e){e.stopPropagation();});menu._dashSelectOwner=wrap;wrap._dashSelectMenu=menu;btn.appendChild(value);btn.appendChild(icon);wrap.appendChild(btn);document.body.appendChild(menu);\n" +
                "    sel.parentNode.insertBefore(wrap,sel);wrap.insertBefore(sel,btn);sel.classList.add('dash-select-native');\n" +
                "    function selectedOption(){return sel.options[sel.selectedIndex]||sel.options[0]||null;}\n" +
                "    function sync(){var opt=selectedOption();value.textContent=opt?opt.textContent.trim():'';wrap.classList.toggle('is-disabled',sel.disabled);btn.disabled=sel.disabled;menu.querySelectorAll('.dash-select-option').forEach(function(item){var active=item.dataset.value===sel.value;item.setAttribute('aria-selected',active?'true':'false');item.setAttribute('data-active',active?'true':'false');});}\n" +
                "    function choose(opt,keepOpen){if(!opt||opt.disabled||sel.disabled)return;sel.value=opt.value;sel.dispatchEvent(new Event('input',{bubbles:true}));sel.dispatchEvent(new Event('change',{bubbles:true}));if(!keepOpen)closeOne(wrap);sync();}\n" +
                "    function rebuild(){menu.innerHTML='';Array.from(sel.options).forEach(function(opt){var item=document.createElement('button');item.type='button';item.className='dash-select-option';item.setAttribute('role','option');item.dataset.value=opt.value;item.disabled=opt.disabled;item.textContent=opt.textContent;item.addEventListener('click',function(e){e.preventDefault();e.stopPropagation();choose(opt);});menu.appendChild(item);});sync();}\n" +
                "    btn.addEventListener('click',function(e){e.preventDefault();e.stopPropagation();if(sel.disabled)return;var open=wrap.getAttribute('data-open')==='true';if(open){closeOne(wrap);}else{sync();openSelect(wrap);}});\n" +
                "    btn.addEventListener('keydown',function(e){var opts=Array.from(sel.options).filter(function(o){return !o.disabled;});if(!opts.length)return;var idx=Math.max(0,opts.indexOf(selectedOption()));if(e.key==='ArrowDown'||e.key==='ArrowUp'){e.preventDefault();idx=e.key==='ArrowDown'?Math.min(opts.length-1,idx+1):Math.max(0,idx-1);choose(opts[idx],true);openSelect(wrap);}else if(e.key==='Escape'){closeAll();btn.focus();}});\n" +
                "    sel.addEventListener('change',sync);if(window.MutationObserver){new MutationObserver(rebuild).observe(sel,{childList:true,subtree:true,attributes:true,attributeFilter:['disabled','label','value','selected']});}rebuild();\n" +
                "  });\n" +
                "  if(!window._dashSelectGlobalBound){window._dashSelectGlobalBound=true;document.addEventListener('click',function(e){var t=e.target;if(t&&t.closest&&(t.closest('.dash-select')||t.closest('.dash-select-menu')))return;closeAll();},true);document.addEventListener('keydown',function(e){if(e.key==='Escape')closeAll();},true);window.addEventListener('resize',positionOpenMenus,{passive:true});window.addEventListener('scroll',function(e){var t=e.target;if(t&&t.closest&&t.closest('.dash-select-menu'))return;positionOpenMenus();},true);}\n" +
                "};\n" +
                "document.addEventListener('DOMContentLoaded',function(){\n" +
                "  if(window.dashInitCustomSelects){window.dashInitCustomSelects(document);}\n" +
                "  document.addEventListener('click',function(e){\n" +
                "    if(e.target.closest&&e.target.closest('#mobile-menu-toggle')){document.body.classList.toggle('sidebar-open');}\n" +
                "    if(e.target.closest&&e.target.closest('#sidebar-overlay')){document.body.classList.remove('sidebar-open');}\n" +
                "  });\n" +
                "});\n" +
                "(function(){\n" +
                "  document.addEventListener('pointerdown',function(e){\n" +
                "    var t=e.target&&e.target.closest?e.target.closest('button:not([disabled]),.dash-sidebar a[href]'):null;\n" +
                "    if(!t)return;\n" +
                "    var r=t.getBoundingClientRect();\n" +
                "    t.style.setProperty('--dash-ripple-x',(e.clientX-r.left)+'px');\n" +
                "    t.style.setProperty('--dash-ripple-y',(e.clientY-r.top)+'px');\n" +
                "    t.classList.remove('dash-ripple');\n" +
                "    void t.offsetWidth;\n" +
                "    t.classList.add('dash-ripple');\n" +
                "    setTimeout(function(){t.classList.remove('dash-ripple');},460);\n" +
                "  },true);\n" +
                "  var busy=false;\n" +
                "  window.dashAnimateContent=function(root){\n" +
                "    root=root||document.getElementById('dash-content');\n" +
                "    var mainEl=root?root.querySelector('main'):document.querySelector('main');\n" +
                "    if(!mainEl)return;\n" +
                "    mainEl.classList.remove('dash-page-out');\n" +
                "    mainEl.classList.add('dash-page-in');\n" +
                "    mainEl.style.willChange='transform,opacity';\n" +
                "    var cards=Array.from(mainEl.children).filter(function(n){return n.nodeType===1;});\n" +
                "    cards.forEach(function(c,i){\n" +
                "      c.style.opacity='0';\n" +
                "      c.style.transform='translate3d(0,10px,0) scale(0.992)';\n" +
                "      c.style.willChange='transform,opacity';\n" +
                "      c.style.transition='opacity 0.38s cubic-bezier(.16,1,.3,1), transform 0.38s cubic-bezier(.16,1,.3,1)';\n" +
                "      c.style.transitionDelay=Math.min(i*0.014,0.09)+'s';\n" +
                "    });\n" +
                "    var rows=Array.from(mainEl.querySelectorAll('tbody tr')).slice(0,120);\n" +
                "    rows.forEach(function(r,i){r.classList.remove('dash-row-in');r.style.animationDelay=Math.min(i*0.012,0.12)+'s';});\n" +
                "    requestAnimationFrame(function(){requestAnimationFrame(function(){\n" +
                "      rows.forEach(function(r){r.classList.add('dash-row-in');});\n" +
                "      cards.forEach(function(c){c.style.opacity='1';c.style.transform='translate3d(0,0,0) scale(1)';});\n" +
                "      setTimeout(function(){cards.forEach(function(c){c.style.transition='';c.style.transform='';c.style.opacity='';c.style.willChange='';c.style.transitionDelay='';});rows.forEach(function(r){r.classList.remove('dash-row-in');r.style.animationDelay='';});mainEl.classList.remove('dash-page-in');mainEl.style.willChange='';},520);\n" +
                "    });});\n" +
                "  };\n" +
                "  function spaNav(url,push,preloadedHtml,preserveScroll){\n" +
                "    if(busy)return Promise.resolve(false);\n" +
                "    var el=document.getElementById('dash-content');\n" +
                "    if(!el){window.location.href=url;return Promise.resolve(false);}\n" +
                "    busy=true;\n" +
                "    document.documentElement.dataset.dashNavigating='true';el.setAttribute('aria-busy','true');\n" +
                "    var priorScroll=el.scrollTop;\n" +
                "    var bar=document.getElementById('dash-progress-bar');\n" +
                "    if(window.dashMotion&&window.dashMotion.progress){window.dashMotion.progress(true);}else if(bar){bar.classList.add('is-active');bar.style.opacity='1';}\n" +
                "    var oldMain=el.querySelector('main');\n" +
                "    if(oldMain){oldMain.classList.remove('dash-page-in','dash-page-out');oldMain.style.willChange='';}\n" +
                "    if(window._dashStatsTimer){clearInterval(window._dashStatsTimer);window._dashStatsTimer=null;}\n" +
                "    if(window._dashPageTimer){clearInterval(window._dashPageTimer);window._dashPageTimer=null;}\n" +
                "    if(window._dashPageTimer2){clearInterval(window._dashPageTimer2);window._dashPageTimer2=null;}\n" +
                "    var request=typeof preloadedHtml==='string'?Promise.resolve(preloadedHtml):fetch(url,{credentials:'same-origin',cache:'no-store'}).then(function(r){\n" +
                "      if(r.redirected){window.location.href=r.url;return null;}\n" +
                "      if(!r.ok)throw new Error(r.status);\n" +
                "      return r.text();\n" +
                "    });\n" +
                "    return request.then(function(html){\n" +
                "      if(!html){if(window.dashMotion&&window.dashMotion.progress){window.dashMotion.progress(false);}else if(bar){bar.classList.remove('is-active');bar.style.opacity='0';}busy=false;el.removeAttribute('aria-busy');delete document.documentElement.dataset.dashNavigating;return false;}\n" +
                "      var doc=new DOMParser().parseFromString(html,'text/html');\n" +
                "      var nc=doc.getElementById('dash-content');\n" +
                "      if(!nc){window.location.href=url;busy=false;el.removeAttribute('aria-busy');delete document.documentElement.dataset.dashNavigating;return false;}\n" +
                "      var apply=function(){\n" +
                "        if(window.dashPageAbortController){try{window.dashPageAbortController.abort();}catch(_){}window.dashPageAbortController=null;}\n" +
                "        el.innerHTML=nc.innerHTML;el.classList.add('dash-spa-mounted');\n" +
                "        if(window.dashInitCustomSelects){window.dashInitCustomSelects(el);}\n" +
                "        var scripts=el.querySelectorAll('script');\n" +
                "        for(var i=0;i<scripts.length;i++){var o=scripts[i];\n" +
                "          if(o.src){var s=document.createElement('script');s.src=o.src;o.parentNode.replaceChild(s,o);}\n" +
                "          else{try{(0,eval)(o.textContent);}catch(ex){console.warn('[Dash SPA]',ex);}o.remove();}}\n" +
                "        updateNav(url);\n" +
                "        el.scrollTop=preserveScroll?priorScroll:0;\n" +
                "        document.body.classList.remove('sidebar-open');\n" +
                "        var tt=doc.querySelector('title');if(tt)document.title=tt.textContent;\n" +
                "        if(push==='replace')history.replaceState(null,'',url);else if(push!==false)history.pushState(null,'',url);\n" +
                "      };\n" +
                "      function reveal(){if(window.dashAnimateContent){window.dashAnimateContent(el);}}\n" +
                "      return new Promise(function(resolve){var settled=false,applied=false,watchdog=null;var applyOnce=function(){if(applied)return;applied=true;apply();};var finish=function(){if(settled)return;settled=true;if(watchdog)clearTimeout(watchdog);if(window.dashMotion&&window.dashMotion.progress){window.dashMotion.progress(false);}else if(bar){bar.classList.remove('is-active');bar.style.opacity='0';}busy=false;el.removeAttribute('aria-busy');delete document.documentElement.dataset.dashNavigating;resolve(true);};watchdog=setTimeout(function(){applyOnce();reveal();finish();},1800);setTimeout(function(){if(window.dashMotion&&window.dashMotion.swap){if(!window.dashMotion.swap(applyOnce,finish)){reveal();finish();}}else{applyOnce();reveal();finish();}},0);});\n" +
                "    }).catch(function(){\n" +
                "      if(window.dashMotion&&window.dashMotion.progress){window.dashMotion.progress(false);}else if(bar){bar.classList.remove('is-active');bar.style.opacity='0';}\n" +
                "      busy=false;\n" +
                "      el.removeAttribute('aria-busy');delete document.documentElement.dataset.dashNavigating;\n" +
                "      window.location.href=url;\n" +
                "      return false;\n" +
                "    });\n" +
                "  }\n" +
                "  function updateNav(url){\n" +
                "    var p;try{p=new URL(url,location.origin).pathname;}catch(e){return;}\n" +
                "    var links=document.querySelectorAll('.dash-sidebar a[href]');\n" +
                "    var ac=['bg-primary/15','text-primary','border-primary/20','font-semibold'];\n" +
                "    var ic=['text-slate-400','border-transparent','hover:bg-white/5','hover:text-white'];\n" +
                "    for(var i=0;i<links.length;i++){var a=links[i],lp=a.getAttribute('href');\n" +
                "      var active=lp==='/'?p==='/':p===lp||p.indexOf(lp+'/')===0;\n" +
                "      for(var j=0;j<ac.length;j++){if(active)a.classList.add(ac[j]);else a.classList.remove(ac[j]);}\n" +
                "      for(var j=0;j<ic.length;j++){if(active)a.classList.remove(ic[j]);else a.classList.add(ic[j]);}\n" +
                "    }\n" +
                "  }\n" +
                "  document.addEventListener('click',function(e){\n" +
                "    if(e.defaultPrevented||e.button!==0||e.metaKey||e.ctrlKey||e.shiftKey||e.altKey)return;\n" +
                "    var a=e.target.closest?e.target.closest('a[href]'):null;\n" +
                "    if(!a)return;var h=a.getAttribute('href');\n" +
                "    if(!h||h.charAt(0)!=='/'||h.indexOf('/api/')===0)return;\n" +
                "    if(a.hasAttribute('download')||(a.getAttribute('target')&&a.getAttribute('target')!=='_self'))return;\n" +
                "    e.preventDefault();spaNav(h,true);\n" +
                "  });\n" +
                "  window.addEventListener('popstate',function(){spaNav(location.pathname+location.search,false);});\n" +
                "  window.dashNavigate=spaNav;\n" +
                "})();\n" +
                "</script>\n" +
                BundledMotion.headTags() +
                I18n.translatorScript(lang) +
                "</head>\n";
    }

    private static String motionLayerStyles() {
        return """
                :root{--dash-dur-fast:.18s;--dash-dur-med:.38s;--dash-ease-expressive:cubic-bezier(.2,.8,.2,1);--dash-ease-spring:cubic-bezier(.16,1,.3,1)}
                :is(.intel-main,.maintenance-main,.ai-main,.ticket-main,.notify-main,.graph-main,.guardian-main){color:#cbd5e1}
                :is(.intel-main,.maintenance-main,.ai-main,.ticket-main,.notify-main,.graph-main,.guardian-main) .rounded-xl{border-radius:12px!important}
                :is(.intel-main,.maintenance-main,.ai-main,.ticket-main,.notify-main,.graph-main,.guardian-main) .rounded-2xl{border-radius:16px!important}
                :is(.intel-main,.maintenance-main,.ai-main,.ticket-main,.notify-main,.graph-main,.guardian-main) .rounded-3xl{border-radius:24px!important}
                :is(.intel-hero,.dash-maintenance-hero,.ai-workspace){border-radius:24px!important}
                :is(.intel-card,.dash-panel,.ai-panel,.ai-consent,.ai-actions,.notify-card,.graph-card,.guardian-card,.ticket-main .ticket-form-grid>article,.ticket-main .ticket-queue article){border-radius:24px!important;background:rgba(15,23,42,.72)!important;box-shadow:0 18px 42px -30px rgba(0,0,0,.9)!important}
                :is(.intel-metrics,.ticket-metrics,.ticket-tabs,.notify-metrics,.notify-tabs,.notify-events,.graph-metrics,.graph-ranges,.ai-tabs){border-radius:16px!important}
                :is(.intel-main,.maintenance-main,.ai-main,.ticket-main,.notify-main,.graph-main,.guardian-main) :is(button,input,select,textarea){border-radius:12px!important}
                :is(.intel-main,.maintenance-main,.ai-main,.ticket-main,.notify-main,.graph-main,.guardian-main) :is(button,a,input,select,textarea):focus-visible{outline:2px solid #22d3ee;outline-offset:2px}
                :is(.notify-tabs,.ticket-tabs,.guardian-tabs){width:100%}
                .dash-intelligence-chevron{margin-left:auto;font-size:18px;transition:transform .32s var(--dash-ease-spring)}
                .dash-sidebar a[href$="/intelligence"][aria-expanded="true"] .dash-intelligence-chevron{transform:rotate(180deg)}
                .dash-intelligence-subnav{display:grid;grid-template-rows:0fr;opacity:0;transition:grid-template-rows .36s var(--dash-ease-spring),opacity .24s ease}
                .dash-intelligence-subnav.is-open{grid-template-rows:1fr;opacity:1}.dash-intelligence-subnav-inner{min-height:0;overflow:hidden;display:grid;gap:3px;padding:3px 0 7px}
                .dash-intelligence-subnav a{min-height:36px;margin:0 8px;padding:8px 14px 8px 45px!important;border:1px solid transparent!important;border-radius:999px!important;color:#64748b!important;font-size:11px!important;transform:none!important}
                .dash-intelligence-subnav a:before{content:'';position:absolute;left:28px;width:5px;height:5px;border-radius:50%;background:#475569;transition:background .2s,box-shadow .2s}
                .dash-intelligence-subnav a:hover,.dash-intelligence-subnav a.is-active{border-color:rgba(34,211,238,.12)!important;background:rgba(34,211,238,.08)!important;color:#cbd5e1!important}.dash-intelligence-subnav a.is-active:before{background:#22d3ee;box-shadow:0 0 0 4px rgba(34,211,238,.1)}
                .dash-customizer-entry{display:flex;align-items:center;gap:12px;min-height:44px;margin-top:5px;padding:10px 20px;border:1px solid transparent;border-radius:999px;background:transparent;color:#94a3b8;position:relative;overflow:hidden;user-select:none;touch-action:pan-y;transition:background .2s ease,color .2s ease,border-color .2s ease}
                .dash-customizer-entry:hover{background:rgba(255,255,255,.05);color:#fff}.dash-customizer-entry small{margin-left:auto;font-size:9px;text-transform:uppercase;color:#475569}.dash-customizer-entry.is-holding{border-color:rgba(34,211,238,.28);background:rgba(34,211,238,.08);color:#a5f3fc}
                :is(.ticket-tabs,.notify-tabs,.ai-tabs,.graph-ranges){padding:4px!important;gap:4px!important;border:1px solid rgba(100,116,139,.25)!important;border-radius:14px!important;background:rgba(2,6,23,.34)!important;overflow:hidden}
                :is(.ticket-tabs,.notify-tabs,.ai-tabs,.graph-ranges) :is(a,button){border:0!important;border-radius:10px!important;background:transparent!important;color:#64748b!important;transition:background .22s ease,color .22s ease,transform .22s ease!important}
                :is(.ticket-tabs,.notify-tabs,.ai-tabs,.graph-ranges) :is(a,button):hover{background:rgba(30,41,59,.72)!important;color:#e2e8f0!important}
                :is(.ticket-tabs,.notify-tabs,.ai-tabs,.graph-ranges) :is(.is-active,[aria-current="page"]){background:rgba(34,211,238,.12)!important;color:#a5f3fc!important;box-shadow:inset 0 0 0 1px rgba(34,211,238,.2)!important}
                :is(.guardian-tab,.guardian-workspace-tab){border-radius:10px!important}
                #dash-content.dash-spa-mounted .dash-card-in,#dash-content.dash-spa-mounted .dash-page-in,#dash-content.dash-spa-mounted .dash-nav-in,#dash-content.dash-spa-mounted .dash-spring-pop,#dash-content.dash-spa-mounted .dash-row-in{animation:none!important}
                @keyframes dashProgressSweep{0%{transform:translate3d(-120%,0,0) scaleX(.36)}45%{transform:translate3d(18%,0,0) scaleX(.82)}100%{transform:translate3d(140%,0,0) scaleX(.42)}}
                @keyframes dashProgressLead{0%{transform:translate3d(-80%,0,0) scaleX(.24)}55%{transform:translate3d(38%,0,0) scaleX(.7)}100%{transform:translate3d(170%,0,0) scaleX(.2)}}
                @keyframes dashAuroraTrack{0%{background-position:0% 50%}100%{background-position:160% 50%}}
                @keyframes dashLoaderDot{0%,80%,100%{transform:translate3d(0,0,0) scale(.72);opacity:.45}40%{transform:translate3d(0,-4px,0) scale(1);opacity:1}}
                @keyframes dashSpinner{to{transform:rotate(360deg)}}
                @keyframes dashSurfaceSheen{0%{transform:translate3d(-135%,0,0) skewX(-18deg);opacity:0}24%{opacity:.7}100%{transform:translate3d(135%,0,0) skewX(-18deg);opacity:0}}
                @keyframes dashStatusPulse{0%{transform:scale(.7);opacity:.55}70%{transform:scale(1.9);opacity:0}100%{transform:scale(1.9);opacity:0}}
                @keyframes dashSkeletonFlow{0%{background-position:220% 0}100%{background-position:-220% 0}}
                #dash-progress-bar{opacity:0;transition:opacity var(--dash-dur-fast) var(--dash-standard),filter var(--dash-dur-fast) var(--dash-standard)!important;contain:paint;transform:translateZ(0)}
                #dash-progress-bar.is-active{opacity:1;filter:drop-shadow(0 0 12px rgba(34,211,238,.35))}
                #dash-progress-bar::before{content:"";position:absolute;inset:0;background:linear-gradient(90deg,rgba(34,211,238,.08),rgba(16,185,129,.28),rgba(255,255,255,.18),rgba(34,211,238,.08));background-size:160% 100%;animation:dashAuroraTrack 1.6s linear infinite}
                #dash-progress-bar .m3-progress-bar-1,#dash-progress-bar .m3-progress-bar-2{position:absolute;inset:0 auto 0 0;width:58%;height:100%;border-radius:999px;transform-origin:left center;will-change:transform}
                #dash-progress-bar .m3-progress-bar-1{background:linear-gradient(90deg,transparent,rgba(34,211,238,.95),rgba(16,185,129,.95),transparent);animation:dashProgressSweep 1.08s var(--dash-ease-expressive) infinite}
                #dash-progress-bar .m3-progress-bar-2{width:44%;background:linear-gradient(90deg,transparent,rgba(255,255,255,.85),rgba(34,211,238,.7),transparent);animation:dashProgressLead 1.38s var(--dash-ease-expressive) infinite .08s}
                #dash-loading-layer{position:fixed;top:14px;right:18px;z-index:100000;display:flex;align-items:center;gap:10px;padding:8px 12px;border:1px solid rgba(148,163,184,.18);border-radius:999px;background:rgba(15,23,42,.82);box-shadow:0 18px 42px -26px rgba(0,0,0,.9),0 0 22px -14px rgba(34,211,238,.8);backdrop-filter:blur(16px);opacity:0;transform:translate3d(0,-8px,0) scale(.98);transition:opacity var(--dash-dur-fast) var(--dash-standard),transform var(--dash-dur-med) var(--dash-ease-expressive);pointer-events:none;will-change:opacity,transform}
                #dash-loading-layer.is-active{opacity:1;transform:translate3d(0,0,0) scale(1)}
                .dash-loader-dots{display:flex;gap:5px;align-items:center}
                .dash-loader-dots i{display:block;width:5px;height:5px;border-radius:999px;background:rgb(34,211,238);box-shadow:0 0 10px rgba(34,211,238,.55);animation:dashLoaderDot .9s ease-in-out infinite}
                .dash-loader-dots i:nth-child(2){animation-delay:.11s;background:rgb(16,185,129)}
                .dash-loader-dots i:nth-child(3){animation-delay:.22s;background:rgb(255,255,255)}
                .dash-submit-loader{display:inline-block;width:16px;height:16px;border:2px solid currentColor;border-right-color:transparent;border-radius:999px;animation:dashSpinner .68s linear infinite;vertical-align:-3px}
                .dash-spinner{width:18px;height:18px;border:2px solid rgba(148,163,184,.35);border-top-color:rgb(34,211,238);border-radius:999px;animation:dashSpinner .72s linear infinite}
                .dash-metric-card,.dash-product-card{position:relative;isolation:isolate;overflow:hidden;contain:layout paint;transform:translateZ(0);backface-visibility:hidden}
                .dash-metric-card>*,.dash-product-card>*{position:relative;z-index:1}
                .dash-metric-card::before,.dash-product-card::before{content:"";position:absolute;inset:-1px;border-radius:inherit;background:radial-gradient(circle at var(--dash-spot-x,50%) var(--dash-spot-y,0%),rgba(34,211,238,.16),transparent 42%),linear-gradient(135deg,rgba(255,255,255,.07),transparent 48%);opacity:0;transition:opacity .26s var(--dash-standard);pointer-events:none;z-index:0}
                .dash-metric-card:hover::before,.dash-product-card:hover::before,.dash-metric-card.is-refreshing::before,.dash-product-card.is-refreshing::before{opacity:1}
                .dash-product-card::after,.dash-metric-card::after{content:"";position:absolute;top:-30%;bottom:-30%;left:-38%;width:42%;background:linear-gradient(90deg,transparent,rgba(255,255,255,.14),transparent);transform:translate3d(-135%,0,0) skewX(-18deg);opacity:0;pointer-events:none;z-index:2}
                .dash-product-card.is-refreshing::after,.dash-metric-card.is-refreshing::after{animation:dashSurfaceSheen .92s var(--dash-ease-expressive)}
                .dash-status-badge{position:relative;isolation:isolate;overflow:hidden;will-change:transform,box-shadow}
                .dash-status-badge::before{content:"";position:absolute;left:10px;top:50%;width:7px;height:7px;border-radius:999px;transform:translateY(-50%);opacity:.8;pointer-events:none}
                .dash-status-badge.is-online::before{background:rgba(16,185,129,.6);animation:dashStatusPulse 1.8s ease-out infinite}
                .dash-status-badge.is-offline::before{background:rgba(244,63,94,.48)}
                .dash-skeleton{color:transparent!important;background:linear-gradient(90deg,rgba(148,163,184,.08) 25%,rgba(148,163,184,.2) 38%,rgba(148,163,184,.08) 63%);background-size:400% 100%;animation:dashSkeletonFlow 1.4s ease-in-out infinite;border-radius:.75rem}
                @media (max-width:767px){#dash-loading-layer{top:62px;left:50%;right:auto;transform:translate3d(-50%,-8px,0) scale(.98)}#dash-loading-layer.is-active{transform:translate3d(-50%,0,0) scale(1)}}
                @media (prefers-reduced-motion: reduce){#dash-progress-bar .m3-progress-bar-1,#dash-progress-bar .m3-progress-bar-2,#dash-progress-bar::before,.dash-loader-dots i,.dash-submit-loader,.dash-spinner,.dash-product-card.is-refreshing::after,.dash-metric-card.is-refreshing::after,.dash-status-badge::before,.dash-skeleton{animation:none!important}}
                """;
    }

    private static String dashLoadingLayer() {
        return "<div id=\"dash-loading-layer\" aria-live=\"polite\" aria-hidden=\"true\"><span class=\"dash-loader-dots\"><i></i><i></i><i></i></span><span class=\"text-[11px] font-semibold uppercase tracking-[0.18em] text-slate-300\">Loading</span></div>\n";
    }

    public static String bodyStart(String currentPath) {
        StringBuilder nav = new StringBuilder();
        boolean betaEnabled = betaFeaturesEnabled();
        int navIdx = 0;
        for (String[] item : NAV_ITEMS) {
            String icon = item[0];
            String label = item[1];
            String path = item[2];
            if (!FeatureFlags.pageVisibleForSetupProfile(path)) continue;
            String requiredPermission = item[3];
            String feature = FeatureFlags.featureForPath(path);
            if (feature != null && !FeatureFlags.enabled(feature)) {
                continue;
            }
            if (isBetaNavItem(path) && !betaEnabled) {
                continue;
            }
            if (!can(requiredPermission)) {
                continue;
            }
            boolean active = path.equals(currentPath);

            String activeClass = active
                    ? "bg-primary/15 text-primary border-primary/20 font-semibold hover:scale-[1.02]"
                    : "text-slate-400 border-transparent hover:bg-white/5 hover:text-white hover:scale-[1.02]";

            nav.append("<a href=\"").append(path)
                    .append("\" class=\"dash-nav-in flex items-center gap-3 px-5 py-3 rounded-full border ").append(activeClass)
                    .append(" transition-all\" style=\"animation-delay:").append(navIdx * 55).append("ms\">\n")
                    .append("<span class=\"material-symbols-outlined text-[20px]\">").append(icon).append("</span>\n")
                    .append("<span class=\"text-sm font-medium\">").append(label).append("</span>");
            navIdx++;

            if ("/updates".equals(path) && isUpdateAvailableForBadge()) {
                nav.append("<span class=\"bg-red-500 w-2 h-2 rounded-full ml-2 animate-pulse\"></span>");
            }
            if ("/intelligence".equals(path)) {
                nav.append("<span class=\"dash-intelligence-chevron material-symbols-outlined\" aria-hidden=\"true\">expand_more</span>");
            }

            nav.append("\n")
                    .append("</a>\n");
            if ("/intelligence".equals(path)) nav.append(intelligenceSubnav(active));
        }

        boolean showBackToNeoDash = Boolean.TRUE.equals(UI_BRIDGE_USER.get());
        String configuredMasterUrl = UI_BRIDGE_MASTER_URL.get();
        String backHref = (configuredMasterUrl == null || configuredMasterUrl.isBlank()) ? "/" : configuredMasterUrl;
        String backToNeoDashHtml = showBackToNeoDash
                ? "<a href=\"" + escapeHtml(backHref)
                        + "\" class=\"mb-3 flex items-center gap-3 px-5 py-3 rounded-full border border-emerald-500/30 bg-emerald-500/10 text-emerald-300 hover:bg-emerald-500/20 transition-all hover:scale-[1.02]\">\n"
                        + "<span class=\"material-symbols-outlined text-[20px]\">arrow_back</span>\n"
                        + "<span class=\"text-sm font-semibold\">Back to NeoDash</span>\n"
                        + "</a>\n"
                : "";

        return "<body class=\"app-shell bg-deep-space text-slate-200 font-body min-h-screen overflow-y-auto selection:bg-primary/30 selection:text-white relative\">\n"
                + "<div class=\"absolute inset-0 bg-[radial-gradient(circle_at_20%_20%,rgba(34,211,238,0.06),transparent_35%),radial-gradient(circle_at_80%_80%,rgba(168,85,247,0.05),transparent_40%)] pointer-events-none z-0\"></div>\n"
                + "<div id=\"dash-progress-bar\" class=\"fixed top-0 left-0 md:left-64 right-0 h-1 bg-primary/10 overflow-hidden opacity-0 pointer-events-none transition-opacity duration-200 z-[99999]\">\n"
                + "<div class=\"m3-progress-bar-1\"></div><div class=\"m3-progress-bar-2\"></div>\n"
                + "</div>\n"
                + dashLoadingLayer()
                + "<nav class=\"sidebar dash-sidebar w-64 flex-shrink-0 flex flex-col min-h-screen bg-gray-900 backdrop-blur-xl border-r border-glass-border p-4\">\n"
                +
                "<div class=\"flex items-center gap-2 px-4 py-4 mb-4\">\n" +
                "<span class=\"material-symbols-outlined text-primary text-[28px]\">dashboard</span>\n" +
                "<span class=\"text-xl font-display font-extrabold text-white tracking-wide\">Dash</span>\n" +
                "</div>\n" +
                "<div class=\"flex flex-col gap-1 flex-1\">\n" +
                nav.toString() + customizerEntry() +
                "</div>\n" +
                "<div class=\"mt-auto pt-4 border-t border-white/5\">\n" +
                backToNeoDashHtml +
                "<form action='/action' method='post'>\n" +
                "<input type='hidden' name='action' value='logout'>\n" +
                "<button class=\"w-full flex items-center gap-3 px-5 py-3 rounded-full text-slate-400 hover:bg-rose-500/10 hover:text-rose-400 transition-all hover:scale-[1.02]\">\n"
                +
                "<span class=\"material-symbols-outlined text-[20px]\">logout</span>\n" +
                "<span class=\"text-sm font-medium\">Logout</span>\n" +
                "</button>\n" +
                "</form>\n" +
                "</div>\n" +
                "</nav>\n" +
                "<div id=\"sidebar-overlay\" class=\"sidebar-overlay\"></div>\n" +
                "<div id=\"dash-content\" class=\"flex-1 flex flex-col h-screen overflow-y-auto overflow-x-hidden w-full pb-24\">\n" +
                "<div class=\"dash-mobile-top md:hidden sticky top-0 z-30 flex items-center gap-3 px-4 py-3 border-b border-white/10 bg-slate-900/95 backdrop-blur\">\n"
                +
                "<button id=\"mobile-menu-toggle\" type=\"button\" class=\"h-9 w-9 rounded-lg border border-white/15 text-slate-200 flex items-center justify-center\">\n"
                +
                "<span class=\"material-symbols-outlined text-[20px]\">menu</span>\n" +
                "</button>\n" +
                "<span class=\"text-sm font-semibold text-slate-100\">Dash</span>\n" +
                "</div>\n";
    }

    private static String sidebarInteractionScript() {
        return """
                <script>
                (function(){
                  var parent=document.querySelector('.dash-sidebar a[href$="/intelligence"],#sidebar a[href$="/intelligence"]');
                  var sub=document.querySelector('[data-intelligence-subnav]');if(!parent||!sub)return;
                  function setOpen(open){sub.classList.toggle('is-open',open);parent.setAttribute('aria-expanded',String(open));}
                  function sync(){var on=location.pathname==='/intelligence',tab=new URLSearchParams(location.search).get('tab')||'lab';if(on)setOpen(true);sub.querySelectorAll('[data-intelligence-tab]').forEach(function(link){link.classList.toggle('is-active',on&&link.dataset.intelligenceTab===tab);});}
                  if(!parent.dataset.intelligenceBound){parent.dataset.intelligenceBound='1';parent.addEventListener('click',function(e){e.preventDefault();setOpen(!sub.classList.contains('is-open'));});}
                  window.dashSyncIntelligenceNav=sync;sync();
                })();
                </script>
                """;
    }


    public static String bodyEnd() {
        return "</div>\n"
                + "<div id=\"dash-command-palette\" class=\"fixed inset-0 z-[100000] hidden items-start justify-center bg-black/60 px-4 pt-[12vh] backdrop-blur-sm\">\n"
                + "<div class=\"w-full max-w-xl rounded-2xl border border-glass-border bg-gray-900/95 p-3 shadow-2xl\">\n"
                + "<input id=\"dash-command-input\" type=\"search\" autocomplete=\"off\" placeholder=\"Search pages and actions...\" class=\"w-full rounded-xl border border-white/10 bg-black/30 px-4 py-3 text-sm text-white outline-none focus:border-primary\">\n"
                + "<div id=\"dash-command-list\" class=\"mt-2 max-h-80 overflow-y-auto console-scrollbar\"></div>\n"
                + "</div></div>\n"
                + dashMotionScript()
                + guardrailReasonScript()
                + "<script>document.querySelectorAll('.dash-sidebar-sheet').forEach(function(node){node.remove();});document.body.classList.remove('dash-sidebar-editing');</script>"
                + sidebarCustomizationScript()
                + sidebarInteractionScript()
                + "<script>\n"
                + "(function(){\n"
                + "  /* The motion engine is deferred, so it has not replaced\n"
                + "     this function yet. Revealing now would run the legacy\n"
                + "     CSS-transition entrance -- the very layer the engine\n"
                + "     supersedes. The definition above remains as a fallback\n"
                + "     for when the engine asset cannot be loaded. */\n"
                + "  var dashFirstReveal=function(){if(window.dashAnimateContent){window.dashAnimateContent(document.getElementById('dash-content'));}};\n"
                + "  if(document.readyState==='loading'){document.addEventListener('DOMContentLoaded',dashFirstReveal,{once:true});}else{dashFirstReveal();}\n"
                + "  if(window.dashInitCustomSelects){window.dashInitCustomSelects(document);}\n"
                + "})();\n"
                + "(function(){\n"
                + "  var palette=document.getElementById('dash-command-palette'),input=document.getElementById('dash-command-input'),list=document.getElementById('dash-command-list');\n"
                + "  if(!palette||!input||!list)return;\n"
                + "  function items(){return Array.from(document.querySelectorAll('.dash-sidebar a[href],#sidebar a[href],[data-dash-command][href]')).map(function(a){return {href:a.getAttribute('href'),label:(a.getAttribute('data-dash-command')||a.textContent||'').trim().replace(/\\s+/g,' ')};}).filter(function(x,i,arr){return x.href&&x.label&&arr.findIndex(function(y){return y.href===x.href&&y.label===x.label;})===i;});}\n"
                + "  function render(){var q=input.value.trim().toLowerCase();var rows=items().filter(function(x){return !q||x.label.toLowerCase().indexOf(q)>=0||x.href.toLowerCase().indexOf(q)>=0;}).slice(0,9);list.innerHTML=rows.length?rows.map(function(x,idx){return '<a class=\"flex items-center justify-between gap-3 rounded-xl px-3 py-2 text-sm text-slate-200 hover:bg-white/10\" href=\"'+x.href+'\"><span class=\"truncate\">'+x.label+'</span><span class=\"text-[11px] text-slate-500\">'+(idx+1)+'</span></a>';}).join(''):'<p class=\"px-3 py-4 text-sm text-slate-500\">No matches</p>';}\n"
                + "  function open(){palette.classList.remove('hidden');palette.classList.add('flex');input.value='';render();setTimeout(function(){input.focus();},0);}\n"
                + "  function close(){palette.classList.add('hidden');palette.classList.remove('flex');}\n"
                + "  document.addEventListener('keydown',function(e){if((e.ctrlKey||e.metaKey)&&String(e.key).toLowerCase()==='k'){e.preventDefault();open();}else if(e.key==='Escape'&&!palette.classList.contains('hidden')){close();}});\n"
                + "  palette.addEventListener('click',function(e){if(e.target===palette)close();});input.addEventListener('input',render);\n"
                + "})();\n"
                + "// Smooth SPA form submissions\n"
                + "document.addEventListener('submit',async function(e){\n"
                + "  if(e.defaultPrevented) return;\n"
                + "  var form=e.target;\n"
                + "  if(!form) return;\n"
                + "  var submitter=e.submitter||null;\n"
                + "  var submitMethod=((submitter&&submitter.getAttribute('formmethod'))||form.getAttribute('method')||'get').toLowerCase();\n"
                + "  if(submitMethod!=='post'||(submitter&&submitter.hasAttribute('data-dash-full-submit'))) return;\n"
                + "  var actionAttr=(submitter&&submitter.getAttribute('formaction'))||form.getAttribute('action')||'';\n"
                + "  var fa=actionAttr.split('?')[0];\n"
                + "  if(fa&&fa!=='/action') return;\n"
                + "  if(form.querySelector('input[type=\"file\"]')) return;\n"
                + "  var data;try{data=new FormData(form);if(submitter&&submitter.name){data.append(submitter.name,submitter.value||'');}}catch(_){return;}\n"
                + "  var actionValue=String(data.get('action')||'').toLowerCase();\n"
                + "  if(['start','restart','stop'].indexOf(actionValue)>=0) return;\n"
                + "  e.preventDefault();\n"
                + "  if(window._dashMutationPending){return;}\n"
                + "  window._dashMutationPending=true;document.documentElement.dataset.dashMutationPending='true';\n"
                + "  var btn=submitter||form.querySelector('button[type=\"submit\"],button:not([type=\"button\"]):not([type=\"reset\"])');\n"
                + "  var origHTML=btn?btn.innerHTML:'';\n"
                + "  if(btn){if(window.dashMotion&&window.dashMotion.busy){window.dashMotion.busy(btn,true,origHTML);}else{btn.disabled=true;btn.style.opacity='0.55';}}\n"
                + "  try{\n"
                + "    var body=new URLSearchParams(data);\n"
                + "    var requestOptions={method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded','X-Requested-With':'dash-mutation'},body:body.toString(),credentials:'same-origin',cache:'no-store'};\n"
                + "    var resp=await fetch('/action',requestOptions);\n"
                + "    if(resp.status===428&&resp.headers.get('X-Dash-Reason-Required')==='1'){var reason=window.dashRequestActionReason?await window.dashRequestActionReason(await resp.text()):null;if(!reason)return;body.set('reason',reason);requestOptions.body=body.toString();resp=await fetch('/action',requestOptions);}\n"
                + "    if(!resp.ok)throw new Error('HTTP '+resp.status);\n"
                + "    var finalUrl=resp.url||window.location.href;\n"
                + "    var responseHtml=await resp.text();\n"
                + "    if(window.dashNavigate){var navigated=await dashNavigate(finalUrl,'replace',responseHtml,true);if(navigated===false)throw new Error('Navigation busy');}\n"
                + "    else window.location.href=finalUrl;\n"
                + "  }catch(err){\n"
                + "    if(window.showToast)window.showToast('Request failed. Please try again.','error');\n"
                + "  }finally{\n"
                + "    window._dashMutationPending=false;delete document.documentElement.dataset.dashMutationPending;\n"
                + "    if(btn&&document.documentElement.contains(btn)){if(window.dashMotion&&window.dashMotion.busy){window.dashMotion.busy(btn,false,origHTML);}else{btn.disabled=false;btn.style.opacity='';btn.innerHTML=origHTML;}}\n"
                + "  }\n"
                + "},false);\n"
                + "</script>\n"
                + "</body></html>";
    }

    private static String guardrailReasonScript() {
        return """
                <script>
                window.dashRequestActionReason=function(message){
                  return new Promise(function(resolve){
                    var old=document.getElementById('dash-guardrail-reason');if(old)old.remove();
                    var layer=document.createElement('div');layer.id='dash-guardrail-reason';layer.setAttribute('role','dialog');layer.setAttribute('aria-modal','true');
                    layer.style.cssText='position:fixed;inset:0;z-index:100001;display:grid;place-items:center;padding:16px;background:rgba(2,6,23,.78);backdrop-filter:blur(10px);opacity:0;transition:opacity .18s ease';
                    layer.innerHTML='<form style="width:min(100%,520px);border:1px solid rgba(251,191,36,.34);border-radius:8px;background:#0f172a;box-shadow:0 24px 80px rgba(0,0,0,.5);overflow:hidden"><div style="padding:20px;border-bottom:1px solid rgba(100,116,139,.3);background:rgba(251,191,36,.08)"><div style="display:flex;align-items:center;gap:12px"><span class="material-symbols-outlined" style="display:grid;place-items:center;width:38px;height:38px;border-radius:7px;background:#fcd34d;color:#111827">policy</span><div><small style="color:#fcd34d;font-weight:800;text-transform:uppercase">Action Guardrail</small><h2 style="margin:3px 0 0;color:#fff;font-size:19px;font-weight:800">Why is this action necessary?</h2></div></div><p data-reason-message style="margin:12px 0 0;color:#94a3b8;font-size:13px;line-height:1.5"></p></div><div style="padding:20px"><label style="display:block;color:#cbd5e1;font-size:12px;font-weight:700">Operator reason<textarea data-reason-input maxlength="500" required rows="4" style="display:block;width:100%;margin-top:7px;border:1px solid #475569;border-radius:6px;background:#020617;padding:11px;color:#fff;resize:vertical;outline:none" placeholder="Describe the operational need and expected outcome"></textarea></label><div style="display:flex;justify-content:flex-end;gap:8px;margin-top:16px"><button type="button" data-reason-cancel style="border:1px solid #475569;border-radius:6px;background:#1e293b;padding:9px 14px;color:#e2e8f0;font-weight:700">Cancel</button><button style="border:0;border-radius:6px;background:#fcd34d;padding:9px 14px;color:#111827;font-weight:800">Continue</button></div></div></form>';
                    document.body.appendChild(layer);layer.querySelector('[data-reason-message]').textContent=message||'An operator reason is required by policy.';
                    var input=layer.querySelector('[data-reason-input]'),settled=false;
                    function done(value){if(settled)return;settled=true;document.removeEventListener('keydown',onKey);layer.style.opacity='0';setTimeout(function(){layer.remove();resolve(value);},150);}
                    function onKey(e){if(e.key==='Escape')done(null);}document.addEventListener('keydown',onKey);
                    layer.querySelector('form').addEventListener('submit',function(e){e.preventDefault();var value=input.value.trim();if(value)done(value);});
                    layer.querySelector('[data-reason-cancel]').addEventListener('click',function(){done(null);});
                    layer.addEventListener('click',function(e){if(e.target===layer)done(null);});
                    requestAnimationFrame(function(){layer.style.opacity='1';input.focus();});
                  });
                };
                </script>
                """;
    }

    private static String dashboardCustomizationScript() {
        return """
                <style>
                .dash-widget-remove{position:absolute;top:-9px;left:-9px;z-index:12;display:none;width:25px;height:25px;place-items:center;border:2px solid #0f172a;border-radius:50%;background:#e2e8f0;color:#0f172a;box-shadow:0 4px 12px rgba(0,0,0,.35)}
                .dash-widget-remove .material-symbols-outlined{font-size:16px;font-weight:900}.dash-widget-editing [data-dash-widget]{position:relative;cursor:default;transform-origin:center}.dash-widget-editing [data-dash-widget] .dash-widget-remove{display:grid}
                .dash-widget-editing [data-dash-widget]{animation:dashWidgetJiggle .23s ease-in-out infinite alternate}.dash-widget-editing [data-dash-widget]:nth-of-type(2n){animation-direction:alternate-reverse;animation-delay:-.11s}.dash-widget-editing [data-dash-widget]:nth-of-type(3n){animation-duration:.27s}
                .dash-widget-sheet{position:fixed;inset:auto 0 0 0;z-index:60;display:none;border-top:1px solid rgba(100,116,139,.35);background:rgba(9,16,29,.97);box-shadow:0 -18px 50px rgba(0,0,0,.45);backdrop-filter:blur(18px)}.dash-widget-editing .dash-widget-sheet{display:block}
                .dash-widget-sheet-inner{width:min(100%,1120px);margin:auto;padding:14px 18px calc(14px + env(safe-area-inset-bottom))}.dash-widget-sheet-head{display:flex;align-items:center;justify-content:space-between;gap:12px}.dash-widget-sheet-head h2{font-size:14px;font-weight:800;color:#f8fafc}.dash-widget-sheet-head p{margin-top:2px;font-size:11px;color:#64748b}.dash-widget-done{border:1px solid rgba(34,211,238,.4);border-radius:6px;background:rgba(8,145,178,.14);padding:7px 12px;color:#a5f3fc;font-size:12px;font-weight:800}.dash-widget-library{display:flex;gap:8px;margin-top:11px;overflow-x:auto;padding:1px 1px 4px}.dash-widget-add{display:inline-flex;flex:0 0 auto;align-items:center;gap:6px;border:1px solid rgba(100,116,139,.32);border-radius:6px;background:rgba(30,41,59,.65);padding:8px 10px;color:#cbd5e1;font-size:11px;font-weight:700}.dash-widget-add:hover{border-color:rgba(34,211,238,.42);color:#a5f3fc}.dash-widget-library-empty{padding:8px 0;color:#64748b;font-size:11px}
                @keyframes dashWidgetJiggle{from{transform:rotate(-.38deg) translate3d(-.2px,0,0)}to{transform:rotate(.38deg) translate3d(.2px,0,0)}}
                @media(max-width:640px){.dash-widget-sheet-inner{padding-inline:12px}.dash-widget-sheet-head p{display:none}}
                @media(prefers-reduced-motion:reduce){.dash-widget-editing [data-dash-widget]{animation:none!important}.dash-widget-remove,.dash-widget-sheet,.dash-widget-add{transition:none!important}}
                </style>
                <script>
                (function(){
                  var reduce=window.matchMedia&&window.matchMedia('(prefers-reduced-motion: reduce)').matches;
                  var root=document.getElementById('dash-content'),main=root&&root.querySelector('main'),trigger=document.getElementById('dash-sidebar-customizer');if(!main||!trigger)return;
                  var all=Array.prototype.slice.call(main.querySelectorAll('[data-dash-widget],.dash-product-card,.dash-metric-card,.dash-panel,.intel-card,.ai-panel,.notify-card,.graph-card,.guardian-card,.ticket-main section,[data-guardian-workspace]>section'));
                  var widgets=all.filter(function(el){return !all.some(function(parent){return parent!==el&&parent.contains(el);});});trigger.hidden=widgets.length<2;if(widgets.length<2)return;var fresh=trigger.cloneNode(true);trigger.replaceWith(fresh);trigger=fresh;
                  function slug(value){return String(value||'panel').toLowerCase().replace(/[^a-z0-9]+/g,'-').replace(/^-|-$/g,'').slice(0,48)||'panel';}
                  var path=location.pathname.replace(/[^a-z0-9/_-]/gi,'_'),key='dash.widgets.hidden.v1:'+path,hidden=[];
                  try{var saved=JSON.parse(localStorage.getItem(key)||'[]');if(Array.isArray(saved))hidden=saved.filter(function(x){return typeof x==='string';});}catch(_){hidden=[];}
                  widgets.forEach(function(el,index){var heading=el.querySelector('h1,h2,h3,[data-widget-title]');var label=(el.getAttribute('data-widget-title')||(heading&&heading.textContent)||el.getAttribute('aria-label')||('Panel '+(index+1))).trim();var id=el.getAttribute('data-dash-widget')||slug(label)+'-'+index;el.setAttribute('data-dash-widget',id);el.setAttribute('data-widget-label',label);if(hidden.indexOf(id)>=0)el.hidden=true;var remove=document.createElement('button');remove.type='button';remove.className='dash-widget-remove';remove.setAttribute('aria-label','Remove '+label);remove.title='Remove panel';remove.innerHTML='<span class="material-symbols-outlined">close</span>';el.appendChild(remove);remove.addEventListener('click',function(e){e.preventDefault();e.stopPropagation();hideWidget(el);});});
                  var sheet=document.createElement('section');sheet.className='dash-widget-sheet';sheet.setAttribute('aria-label','Available panels');sheet.innerHTML='<div class="dash-widget-sheet-inner"><div class="dash-widget-sheet-head"><div><h2>Customize this page</h2><p>Remove panels above or add hidden panels back.</p></div><button type="button" class="dash-widget-done">Done</button></div><div class="dash-widget-library"></div></div>';document.body.appendChild(sheet);var library=sheet.querySelector('.dash-widget-library');
                  function persist(){try{localStorage.setItem(key,JSON.stringify(hidden));}catch(_){}}
                  function animate(el,frames,options,done){if(reduce||!el.animate){if(done)done();return;}var animation=el.animate(frames,options);if(done)animation.addEventListener('finish',done,{once:true});}
                  function hideWidget(el){var id=el.getAttribute('data-dash-widget');if(hidden.indexOf(id)<0)hidden.push(id);persist();if(reduce){el.hidden=true;renderLibrary();return;}animate(el,[{opacity:1,transform:'scale(1)',maxHeight:el.offsetHeight+'px'},{opacity:0,transform:'scale(.94)',maxHeight:'0px'}],{duration:260,easing:'cubic-bezier(.4,0,.2,1)'},function(){el.hidden=true;renderLibrary();});}
                  function showWidget(el){hidden=hidden.filter(function(id){return id!==el.getAttribute('data-dash-widget');});persist();el.hidden=false;renderLibrary();animate(el,[{opacity:0,transform:'translateY(10px) scale(.96)'},{opacity:1,transform:'translateY(0) scale(1)'}],{duration:360,easing:'cubic-bezier(.16,1,.3,1)'});el.scrollIntoView({block:'nearest',behavior:reduce?'auto':'smooth'});}
                  function renderLibrary(){var missing=widgets.filter(function(el){return el.hidden;});library.innerHTML='';if(!missing.length){library.innerHTML='<p class="dash-widget-library-empty">All available panels are visible.</p>';return;}missing.forEach(function(el){var button=document.createElement('button');button.type='button';button.className='dash-widget-add';button.innerHTML='<span class="material-symbols-outlined" style="font-size:16px">add</span><span></span>';button.querySelector('span:last-child').textContent=el.getAttribute('data-widget-label');button.addEventListener('click',function(){showWidget(el);});library.appendChild(button);});}
                  function enter(){document.body.classList.add('dash-widget-editing');trigger.hidden=true;renderLibrary();sheet.querySelector('.dash-widget-done').focus();}function leave(){document.body.classList.remove('dash-widget-editing');trigger.hidden=false;trigger.focus();}
                  trigger.addEventListener('click',function(e){e.preventDefault();enter();});var holdTimer=null,held=false;function cancelSidebarHold(){clearTimeout(holdTimer);holdTimer=null;trigger.classList.remove('is-holding');}trigger.addEventListener('pointerdown',function(e){if(e.button!==0)return;held=false;trigger.classList.add('is-holding');holdTimer=setTimeout(function(){held=true;cancelSidebarHold();if(navigator.vibrate)navigator.vibrate(18);enter();},520);});trigger.addEventListener('pointerup',cancelSidebarHold);trigger.addEventListener('pointercancel',cancelSidebarHold);trigger.addEventListener('pointerleave',cancelSidebarHold);sheet.querySelector('.dash-widget-done').addEventListener('click',leave);document.addEventListener('keydown',function(e){if(e.key==='Escape'&&document.body.classList.contains('dash-widget-editing'))leave();});
                  var timer=null,startX=0,startY=0;main.addEventListener('pointerdown',function(e){var card=e.target.closest&&e.target.closest('[data-dash-widget]');if(!card||e.button!==0||document.body.classList.contains('dash-widget-editing'))return;startX=e.clientX;startY=e.clientY;timer=setTimeout(enter,560);},{passive:true});function cancel(){if(timer){clearTimeout(timer);timer=null;}}main.addEventListener('pointermove',function(e){if(Math.abs(e.clientX-startX)>8||Math.abs(e.clientY-startY)>8)cancel();},{passive:true});main.addEventListener('pointerup',cancel,{passive:true});main.addEventListener('pointercancel',cancel,{passive:true});main.addEventListener('contextmenu',cancel);
                })();
                </script>
                """;
    }

    private static String dashMotionScript() {
        return """
                <script>
                /* Dash Motion Engine 5 bridge. The engine itself lives in
                   /assets/dash-motion-5.js and installs its own interaction,
                   entrance and scroll-reveal layers. This block only connects
                   the two host behaviours the engine cannot discover on its
                   own: the toast factory and the custom-select popover. */
                (function(){
                  function bridge(){
                    var M=window.DashMotion;
                    if(!M){return;}
                    var base=window.showToast;
                    if(base&&!base.__dashMotionWrapped){
                      var wrapped=function(){
                        var result=base.apply(this,arguments);
                        var host=document.getElementById('dash-toast-host');
                        var el=host&&host.lastElementChild;
                        if(el){
                          M.toastIn(el);
                          setTimeout(function(){if(el.parentNode){M.toastOut(el);}},3400);
                        }
                        return result;
                      };
                      wrapped.__dashMotionWrapped=true;
                      window.showToast=wrapped;
                    }
                    document.addEventListener('click',function(e){
                      var btn=e.target&&e.target.closest?e.target.closest('.nd-select-button,.dash-select-button'):null;
                      if(!btn){return;}
                      setTimeout(function(){
                        var wrap=btn.closest('.nd-select,.dash-select');
                        var menu=wrap&&(wrap._ndSelectMenu||wrap._dashSelectMenu);
                        if(wrap&&wrap.getAttribute('data-open')==='true'&&menu){M.popIn(menu);}
                      },0);
                    },true);
                  }
                  if(document.readyState==='loading'){document.addEventListener('DOMContentLoaded',bridge,{once:true});}
                  else{bridge();}
                })();
                </script>
                """;
    }

    public static String page(String title, String currentPath, String content) {
        return head(title) + bodyStart(currentPath) + content + releaseIntroduction(currentPath) + bodyEnd();
    }

    private static String releaseIntroduction(String currentPath) {
        if (currentPath == null || currentPath.equals("/login") || currentPath.equals("/setup") || currentPath.equals("/register")) return "";
        return "<div id='dash-release-intro' class='fixed inset-0 z-[100] hidden items-center justify-center bg-slate-950/75 p-4 backdrop-blur-sm' role='dialog' aria-modal='true' aria-labelledby='dash-release-title'>"
                + "<section class='w-full max-w-xl rounded-2xl border border-primary/30 bg-slate-900 p-6 shadow-2xl'><p class='text-xs font-bold uppercase tracking-wider text-primary'>Dash 4.5.1</p>"
                + "<h2 id='dash-release-title' class='mt-2 text-2xl font-bold text-white'>Intelligence that acts</h2><p class='mt-2 text-sm text-slate-300'>Investigate failures, protect every change and run player care, policy and reliability from one workspace.</p>"
                + "<div class='mt-5 grid gap-3 sm:grid-cols-3'><div class='rounded-xl border border-slate-700 p-3'><p class='font-bold text-white'>Investigate and recover</p><p class='mt-1 text-xs text-slate-400'>Shadow Boot, root cause evidence and transactional state restore.</p></div>"
                + "<div class='rounded-xl border border-slate-700 p-3'><p class='font-bold text-white'>Protect every action</p><p class='mt-1 text-xs text-slate-400'>Guardrails, dual control and just-in-time staff access.</p></div>"
                + "<div class='rounded-xl border border-slate-700 p-3'><p class='font-bold text-white'>Operate the service</p><p class='mt-1 text-xs text-slate-400'>Player care, supply chain, war rooms and public status.</p></div></div>"
                + "<div class='mt-6 flex flex-col-reverse gap-2 sm:flex-row sm:justify-end'><button type='button' data-release-close class='rounded-xl border border-slate-700 px-4 py-2.5 text-sm font-bold text-slate-200'>Continue</button><a href='/intelligence' data-release-close class='rounded-xl bg-primary px-4 py-2.5 text-center text-sm font-bold text-black'>Open Intelligence</a></div></section></div>"
                + releaseIntroductionScript("dash-release-intro-4.5.1");
    }

    private static String releaseIntroductionScript(String storageKey) {
        return "<script>(function(){var key='" + storageKey + "',layer=document.getElementById('dash-release-intro');if(!layer)return;var cookieKey=key.replace(/[^A-Za-z0-9_-]/g,'_'),seen=false;try{seen=localStorage.getItem(key)==='1';}catch(e){}if(!seen){seen=document.cookie.split(';').some(function(value){return value.trim()===cookieKey+'=1';});}if(seen)return;var remember=function(){try{localStorage.setItem(key,'1');}catch(e){}document.cookie=cookieKey+'=1; Max-Age=31536000; Path=/; SameSite=Lax'+(location.protocol==='https:'?'; Secure':'');};remember();layer.classList.remove('hidden');layer.classList.add('flex');var reduce=window.matchMedia&&window.matchMedia('(prefers-reduced-motion: reduce)').matches,card=layer.querySelector('section');if(!reduce&&layer.animate){layer.animate([{opacity:0},{opacity:1}],{duration:200,easing:'ease-out'});if(card)card.animate([{opacity:0,transform:'translate3d(0,18px,0) scale(.97)'},{opacity:1,transform:'translate3d(0,0,0) scale(1)'}],{duration:480,easing:'cubic-bezier(.16,1,.3,1)'});}var button=layer.querySelector('button');if(button)button.focus();var close=function(){remember();layer.classList.add('hidden');layer.classList.remove('flex');};layer.querySelectorAll('[data-release-close]').forEach(function(el){el.addEventListener('click',close);});layer.addEventListener('click',function(e){if(e.target===layer)close();});document.addEventListener('keydown',function(e){if(e.key==='Escape')close();});})();</script>";
    }

    public static String authPage(String title, String content) {
        return head(title) +
                "<body class=\"bg-deep-space text-slate-200 font-display min-h-screen flex items-center justify-center px-4\">\n" +
                "<div class=\"absolute inset-0 bg-[radial-gradient(circle_at_20%_20%,rgba(14,165,233,0.15),transparent_28%),radial-gradient(circle_at_80%_0%,rgba(56,189,248,0.1),transparent_32%)]\"></div>\n"
                +
                "<div class=\"relative z-10 page-shell w-full max-w-md\">\n" +
                content +
                "</div>\n" +
                "</body></html>";
    }

    public static String statsHeader() {
        String serverActions = "";
        if (can("dash.web.server.control")) {
            serverActions = "<form action='/action' method='post' style='display:inline'><input type='hidden' name='action' value='restart'>\n"
                    +
                    "<button class=\"flex items-center gap-2 px-6 py-3 rounded-full bg-primary/10 border border-primary/20 text-primary hover:bg-primary hover:text-black hover:shadow-glow-primary transition-all duration-300 group\">\n"
                    +
                    "<span class=\"material-symbols-outlined text-[20px] group-hover:animate-spin\">refresh</span>\n"
                    +
                    "<span class=\"text-sm font-semibold\">Restart</span>\n" +
                    "</button></form>\n" +
                    "<form action='/action' method='post' style='display:inline' onsubmit=\"return confirm('STOP SERVER?');\">\n"
                    +
                    "<input type='hidden' name='action' value='stop'>\n" +
                    "<button class=\"flex items-center gap-2 px-6 py-3 rounded-full bg-rose-500/10 border border-rose-500/20 text-rose-400 hover:bg-rose-600 hover:text-white hover:shadow-glow-danger transition-all duration-300\">\n"
                    +
                    "<span class=\"material-symbols-outlined text-[20px]\">power_settings_new</span>\n" +
                    "<span class=\"text-sm font-semibold\">Stop</span>\n" +
                    "</button></form>\n";
        }

        return "<header class=\"w-full px-6 py-4 flex-shrink-0\">\n" +
                "<div class=\"grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-[repeat(3,minmax(0,1fr))_auto] gap-4 items-stretch\">\n" +
                "<div class=\"group dash-metric-card dash-hover-lift min-w-0 flex items-center justify-between p-4 rounded-2xl bg-glass-surface backdrop-blur-xl border border-glass-border hover:bg-glass-highlight transition-all duration-300\">\n"
                +
                "<div class=\"flex flex-col gap-1\">\n" +
                "<span class=\"text-xs font-medium text-slate-400 uppercase tracking-wider\">Server Uptime</span>\n" +
                "<span id=\"uptime-val\" data-live-metric-value class=\"text-xl font-bold text-white tracking-tight\">--</span>\n" +
                "</div>\n" +
                "<div class=\"h-10 w-10 rounded-full bg-emerald-500/10 flex items-center justify-center text-emerald-400 group-hover:scale-110 transition-transform\">\n"
                +
                "<span class=\"material-symbols-outlined text-[20px]\">dns</span>\n" +
                "</div>\n" +
                "</div>\n" +
                "<div class=\"group dash-metric-card dash-hover-lift min-w-0 flex items-center justify-between p-4 rounded-2xl bg-glass-surface backdrop-blur-xl border border-glass-border hover:bg-glass-highlight transition-all duration-300\">\n"
                +
                "<div class=\"flex flex-col gap-1\">\n" +
                "<span class=\"text-xs font-medium text-slate-400 uppercase tracking-wider\">TPS</span>\n" +
                "<span id=\"tps-val\" data-live-metric-value class=\"text-xl font-bold text-white tracking-tight\">--</span>\n" +
                "</div>\n" +
                "<div class=\"h-10 w-10 rounded-full bg-primary/10 flex items-center justify-center text-primary group-hover:scale-110 transition-transform\">\n"
                +
                "<span class=\"material-symbols-outlined text-[20px]\">speed</span>\n" +
                "</div>\n" +
                "</div>\n" +
                "<div class=\"group dash-metric-card dash-hover-lift min-w-0 flex items-center justify-between p-4 rounded-2xl bg-glass-surface backdrop-blur-xl border border-glass-border hover:bg-glass-highlight transition-all duration-300\">\n"
                +
                "<div class=\"flex flex-col gap-1\">\n" +
                "<span class=\"text-xs font-medium text-slate-400 uppercase tracking-wider\">RAM Usage</span>\n" +
                "<span id=\"ram-val\" data-live-metric-value class=\"text-xl font-bold text-white tracking-tight\">--</span>\n" +
                "</div>\n" +
                "<div class=\"h-10 w-10 rounded-full bg-amber-500/10 flex items-center justify-center text-amber-400 group-hover:scale-110 transition-transform\">\n"
                +
                "<span class=\"material-symbols-outlined text-[20px]\">memory</span>\n" +
                "</div>\n" +
                "</div>\n" +
                "<div class=\"flex flex-wrap items-center justify-start sm:col-span-2 xl:col-span-1 xl:justify-end gap-3 min-w-0\">\n" +
                serverActions +
                "</div>\n" +
                "</div>\n" +
                "</header>\n";
    }

    public static String statsScript() {
        return "<script>\n" +
                "if(window._dashStatsTimer){clearInterval(window._dashStatsTimer);window._dashStatsTimer=null;}\n" +
                "function pollStats() {\n" +
                "  fetch('/api/stats').then(r => r.json()).then(d => {\n" +
                "    if(d.error) return;\n" +
                "    var e;\n" +
                "    e=document.getElementById('tps-val'); if(e) { if(window.dashSetText) window.dashSetText(e,d.tps.toFixed(1)); else e.innerText = d.tps.toFixed(1); }\n" +
                "    e=document.getElementById('ram-val'); if(e) { var ramText=d.ram_used + ' / ' + d.ram_max + ' MB'; if(window.dashSetText) window.dashSetText(e,ramText); else e.innerText = ramText; }\n" +
                "    e=document.getElementById('uptime-val'); if(e) { if(window.dashSetText) window.dashSetText(e,d.uptime,'dash-status-flip'); else e.innerText = d.uptime; }\n" +
                "  }).catch(function(){});\n" +
                "}\n" +
                "window._dashStatsTimer = setInterval(pollStats, 2000);\n" +
                "pollStats();\n" +
                "</script>\n";
    }

    private static String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static boolean isUpdateAvailableForBadge() {
        try {
            GithubUpdater updater = Dash.getGithubUpdater();
            return updater != null && updater.isEnabled() && updater.isUpdateAvailable();
        } catch (Exception ignored) {
            return false;
        }
    }
}
