package dash.web;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Client-side UI translation support. Pages remain authored in English; a small
 * client-side runtime translates visible text nodes and a few attributes using a
 * dictionary injected per request based on the signed-in user's preferred
 * language.
 *
 * The dictionary is intentionally compact and high-impact. Untranslated strings
 * simply remain in English.
 */
public final class I18n {

    public static final String DEFAULT_LANGUAGE = "en";

    /** Supported languages (code -> English display name). Keeps the dropdown stable and small. */
    public static final Map<String, String> SUPPORTED;
    static {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        m.put("en", "English");
        m.put("de", "German");
        m.put("fr", "French");
        m.put("es", "Spanish");
        m.put("it", "Italian");
        m.put("pt", "Portuguese");
        m.put("nl", "Dutch");
        m.put("pl", "Polish");
        m.put("ru", "Russian");
        m.put("tr", "Turkish");
        m.put("zh", "Chinese (Simplified)");
        m.put("ja", "Japanese");
        SUPPORTED = Collections.unmodifiableMap(m);
    }

    private static final Map<String, Map<String, String>> TRANSLATIONS = buildTranslations();

    private I18n() {
    }

    public static String normalize(String code) {
        if (code == null) {
            return DEFAULT_LANGUAGE;
        }
        String c = code.trim().toLowerCase(Locale.ROOT);
        if (c.isEmpty()) return DEFAULT_LANGUAGE;
        // Accept "en_US", "en-US", etc. Keep only the language portion.
        int dash = c.indexOf('-');
        int under = c.indexOf('_');
        int cut = -1;
        if (dash >= 0 && under >= 0) cut = Math.min(dash, under);
        else if (dash >= 0) cut = dash;
        else if (under >= 0) cut = under;
        if (cut > 0) c = c.substring(0, cut);
        return SUPPORTED.containsKey(c) ? c : DEFAULT_LANGUAGE;
    }

    /**
     * Returns a &lt;select&gt;-ready options fragment, marking {@code currentCode}
     * as selected. Kept minimal so page renderers can drop it in anywhere.
     */
    public static String optionsHtml(String currentCode) {
        String cur = normalize(currentCode);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : SUPPORTED.entrySet()) {
            sb.append("<option value=\"").append(e.getKey()).append("\"")
              .append(e.getKey().equals(cur) ? " selected" : "")
              .append(">").append(e.getValue()).append("</option>");
        }
        return sb.toString();
    }

    /** Script tag that injects the language + dictionary + DOM translator. */
    public static String translatorScript(String currentCode) {
        String lang = normalize(currentCode);
        Map<String, String> dict = TRANSLATIONS.getOrDefault(lang, Map.of());
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : new TreeMap<>(dict).entrySet()) {
            if (!first) json.append(",");
            first = false;
            json.append(jsString(e.getKey())).append(":").append(jsString(e.getValue()));
        }
        json.append("}");
        return "<script>\n"
                + "window.__dashLang=" + jsString(lang) + ";\n"
                + "window.__dashI18n=" + json + ";\n"
                + TRANSLATOR_JS
                + "</script>\n";
    }

    private static String jsString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"':  sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '<':  sb.append("\\u003c"); break;
                case '>':  sb.append("\\u003e"); break;
                case '&':  sb.append("\\u0026"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append("\"");
        return sb.toString();
    }

    private static Map<String, Map<String, String>> buildTranslations() {
        Map<String, Map<String, String>> all = new LinkedHashMap<>();
        // --- German ---
        Map<String, String> de = new LinkedHashMap<>();
        putNav(de, "Dashboard", "Übersicht", "Console", "Konsole", "Players", "Spieler", "Files", "Dateien",
                "Plugins", "Plugins", "Users", "Benutzer", "Permissions", "Berechtigungen", "Settings", "Einstellungen",
                "Audit Log", "Audit-Log", "Tasks", "Aufgaben", "Updates", "Aktualisierungen",
                "Plugin Settings", "Plugin-Einstellungen", "Servers", "Server", "Scan Servers", "Server scannen",
                "Create Server", "Server erstellen", "Profile", "Profil", "Menu", "Menü");
        putCommon(de, "Save", "Speichern", "Save Settings", "Einstellungen speichern", "Save Setting", "Einstellung speichern",
                "Cancel", "Abbrechen", "Delete", "Löschen", "Edit", "Bearbeiten", "Add", "Hinzufügen", "Remove", "Entfernen",
                "Create", "Erstellen", "Update", "Aktualisieren", "Confirm", "Bestätigen", "Logout", "Abmelden",
                "Login", "Anmelden", "Back", "Zurück", "Refresh", "Aktualisieren", "Stop", "Stoppen",
                "Start", "Starten", "Restart", "Neustart", "Download", "Herunterladen", "Upload", "Hochladen",
                "Saving...", "Wird gespeichert...", "Loading...", "Wird geladen...", "Error", "Fehler",
                "Success", "Erfolg", "Warning", "Warnung", "Info", "Info",
                "Username", "Benutzername", "Password", "Passwort", "Role", "Rolle", "Create User", "Benutzer erstellen",
                "Delete User", "Benutzer löschen", "Update Role", "Rolle aktualisieren", "Make Main-Admin", "Zum Haupt-Admin machen",
                "Global Role", "Globale Rolle", "User & Role Management", "Benutzer- und Rollenverwaltung",
                "Server Uptime", "Server-Laufzeit", "TPS", "TPS", "RAM Usage", "RAM-Nutzung",
                "Language", "Sprache", "UI Language", "Anzeigesprache",
                "General Settings", "Allgemeine Einstellungen", "Web Port", "Web-Port", "Panel URL", "Panel-URL",
                "Max Backups", "Max. Backups", "NeoBridge Settings", "NeoBridge-Einstellungen", "Bridge Enabled", "Bridge aktiviert",
                "Bridge Secret", "Bridge-Geheimnis", "NeoDash Master URL", "NeoDash Master-URL",
                "Discord Webhooks", "Discord-Webhooks", "Add Webhook", "Webhook hinzufügen",
                "Audit Logs", "Audit-Logs", "Player Chat", "Spieler-Chat", "Server Start/Stop", "Server Start/Stop",
                "Console Warnings", "Konsolenwarnungen",
                "Update Check Interval", "Aktualisierungs-Prüfintervall", "Interval (minutes)", "Intervall (Minuten)",
                "SSO Enabled", "SSO aktiviert", "Global SSO Secret (optional)", "Globales SSO-Geheimnis (optional)",
                "Save SSO Settings", "SSO-Einstellungen speichern",
                "Destruction Zone", "Zerstörungsbereich", "Destroy", "Zerstören",
                "Settings saved successfully", "Einstellungen erfolgreich gespeichert",
                "Failed to save settings", "Einstellungen konnten nicht gespeichert werden",
                "Language preference saved.", "Sprachpräferenz gespeichert.",
                "Are you sure?", "Sind Sie sicher?", "Yes", "Ja", "No", "Nein",
                "Choose the display language for the admin panel. Saved in your browser.",
                "Wählen Sie die Anzeigesprache für das Admin-Panel. Gespeichert in Ihrem Konto.",
                "Select the display language for the NeoDash admin panel. Your preference is saved locally in this browser.",
                "Wählen Sie die Anzeigesprache für das NeoDash-Admin-Panel. Ihre Präferenz wird in Ihrem Konto gespeichert.",
                "Global NeoDash configuration.", "Globale NeoDash-Konfiguration.");
        all.put("de", de);

        // --- French ---
        Map<String, String> fr = new LinkedHashMap<>();
        putNav(fr, "Dashboard", "Tableau de bord", "Console", "Console", "Players", "Joueurs", "Files", "Fichiers",
                "Plugins", "Plugins", "Users", "Utilisateurs", "Permissions", "Permissions", "Settings", "Paramètres",
                "Audit Log", "Journal d'audit", "Tasks", "Tâches", "Updates", "Mises à jour",
                "Plugin Settings", "Paramètres du plugin", "Servers", "Serveurs", "Scan Servers", "Analyser les serveurs",
                "Create Server", "Créer un serveur", "Profile", "Profil", "Menu", "Menu");
        putCommon(fr, "Save", "Enregistrer", "Save Settings", "Enregistrer les paramètres", "Save Setting", "Enregistrer le paramètre",
                "Cancel", "Annuler", "Delete", "Supprimer", "Edit", "Modifier", "Add", "Ajouter", "Remove", "Retirer",
                "Create", "Créer", "Update", "Mettre à jour", "Confirm", "Confirmer", "Logout", "Déconnexion",
                "Login", "Connexion", "Back", "Retour", "Refresh", "Actualiser", "Stop", "Arrêter",
                "Start", "Démarrer", "Restart", "Redémarrer", "Download", "Télécharger", "Upload", "Envoyer",
                "Saving...", "Enregistrement...", "Loading...", "Chargement...", "Error", "Erreur",
                "Success", "Succès", "Warning", "Avertissement", "Info", "Info",
                "Username", "Nom d'utilisateur", "Password", "Mot de passe", "Role", "Rôle", "Create User", "Créer un utilisateur",
                "Delete User", "Supprimer l'utilisateur", "Update Role", "Mettre à jour le rôle", "Make Main-Admin", "Nommer Admin principal",
                "Global Role", "Rôle global", "User & Role Management", "Gestion des utilisateurs et rôles",
                "Server Uptime", "Disponibilité du serveur", "TPS", "TPS", "RAM Usage", "Utilisation RAM",
                "Language", "Langue", "UI Language", "Langue de l'interface",
                "General Settings", "Paramètres généraux", "Web Port", "Port web", "Panel URL", "URL du panneau",
                "Max Backups", "Sauvegardes max.", "NeoBridge Settings", "Paramètres NeoBridge", "Bridge Enabled", "Bridge activé",
                "Bridge Secret", "Secret du bridge", "NeoDash Master URL", "URL maître NeoDash",
                "Discord Webhooks", "Webhooks Discord", "Add Webhook", "Ajouter un webhook",
                "Audit Logs", "Journaux d'audit", "Player Chat", "Chat des joueurs", "Server Start/Stop", "Démarrage/Arrêt du serveur",
                "Console Warnings", "Avertissements de la console",
                "Update Check Interval", "Intervalle de vérification des mises à jour", "Interval (minutes)", "Intervalle (minutes)",
                "SSO Enabled", "SSO activé", "Global SSO Secret (optional)", "Secret SSO global (optionnel)",
                "Save SSO Settings", "Enregistrer les paramètres SSO",
                "Destruction Zone", "Zone de destruction", "Destroy", "Détruire",
                "Settings saved successfully", "Paramètres enregistrés avec succès",
                "Failed to save settings", "Échec de l'enregistrement des paramètres",
                "Language preference saved.", "Préférence de langue enregistrée.",
                "Are you sure?", "Êtes-vous sûr ?", "Yes", "Oui", "No", "Non",
                "Choose the display language for the admin panel. Saved in your browser.",
                "Choisissez la langue d'affichage du panneau admin. Enregistrée dans votre compte.",
                "Select the display language for the NeoDash admin panel. Your preference is saved locally in this browser.",
                "Sélectionnez la langue d'affichage du panneau NeoDash. Votre préférence est enregistrée dans votre compte.",
                "Global NeoDash configuration.", "Configuration globale de NeoDash.");
        all.put("fr", fr);

        // --- Spanish ---
        Map<String, String> es = new LinkedHashMap<>();
        putNav(es, "Dashboard", "Panel", "Console", "Consola", "Players", "Jugadores", "Files", "Archivos",
                "Plugins", "Complementos", "Users", "Usuarios", "Permissions", "Permisos", "Settings", "Ajustes",
                "Audit Log", "Registro de auditoría", "Tasks", "Tareas", "Updates", "Actualizaciones",
                "Plugin Settings", "Ajustes del complemento", "Servers", "Servidores", "Scan Servers", "Escanear servidores",
                "Create Server", "Crear servidor", "Profile", "Perfil", "Menu", "Menú");
        putCommon(es, "Save", "Guardar", "Save Settings", "Guardar ajustes", "Save Setting", "Guardar ajuste",
                "Cancel", "Cancelar", "Delete", "Eliminar", "Edit", "Editar", "Add", "Añadir", "Remove", "Quitar",
                "Create", "Crear", "Update", "Actualizar", "Confirm", "Confirmar", "Logout", "Cerrar sesión",
                "Login", "Iniciar sesión", "Back", "Atrás", "Refresh", "Actualizar", "Stop", "Detener",
                "Start", "Iniciar", "Restart", "Reiniciar", "Download", "Descargar", "Upload", "Subir",
                "Saving...", "Guardando...", "Loading...", "Cargando...", "Error", "Error",
                "Success", "Éxito", "Warning", "Advertencia", "Info", "Info",
                "Username", "Nombre de usuario", "Password", "Contraseña", "Role", "Rol", "Create User", "Crear usuario",
                "Delete User", "Eliminar usuario", "Update Role", "Actualizar rol", "Make Main-Admin", "Hacer admin principal",
                "Global Role", "Rol global", "User & Role Management", "Gestión de usuarios y roles",
                "Server Uptime", "Tiempo activo del servidor", "TPS", "TPS", "RAM Usage", "Uso de RAM",
                "Language", "Idioma", "UI Language", "Idioma de la interfaz",
                "General Settings", "Ajustes generales", "Web Port", "Puerto web", "Panel URL", "URL del panel",
                "Max Backups", "Copias máx.", "NeoBridge Settings", "Ajustes de NeoBridge", "Bridge Enabled", "Bridge activado",
                "Bridge Secret", "Secreto del bridge", "NeoDash Master URL", "URL maestra de NeoDash",
                "Discord Webhooks", "Webhooks de Discord", "Add Webhook", "Añadir webhook",
                "Audit Logs", "Registros de auditoría", "Player Chat", "Chat de jugadores", "Server Start/Stop", "Inicio/Parada del servidor",
                "Console Warnings", "Advertencias de consola",
                "Update Check Interval", "Intervalo de comprobación de actualizaciones", "Interval (minutes)", "Intervalo (minutos)",
                "SSO Enabled", "SSO activado", "Global SSO Secret (optional)", "Secreto SSO global (opcional)",
                "Save SSO Settings", "Guardar ajustes SSO",
                "Destruction Zone", "Zona de destrucción", "Destroy", "Destruir",
                "Settings saved successfully", "Ajustes guardados correctamente",
                "Failed to save settings", "No se pudieron guardar los ajustes",
                "Language preference saved.", "Preferencia de idioma guardada.",
                "Are you sure?", "¿Está seguro?", "Yes", "Sí", "No", "No",
                "Choose the display language for the admin panel. Saved in your browser.",
                "Elija el idioma de visualización del panel de administración. Guardado en su cuenta.",
                "Select the display language for the NeoDash admin panel. Your preference is saved locally in this browser.",
                "Seleccione el idioma de visualización del panel NeoDash. Su preferencia se guarda en su cuenta.",
                "Global NeoDash configuration.", "Configuración global de NeoDash.");
        putPairs(es,
                "Dash AI", "IA de Dash", "Intelligence", "Inteligencia", "Guardian", "Guardian",
                "Tickets", "Tickets", "Notifications", "Notificaciones", "Graphs", "Gráficos",
                "Maintenance", "Mantenimiento", "Staff", "Personal", "Backups", "Copias de seguridad",
                "Overview", "Resumen", "Destinations", "Destinos", "Events", "Eventos",
                "Delivery Health", "Estado de entrega", "Inbox", "Bandeja de entrada", "My Tickets", "Mis tickets",
                "New Report", "Nuevo informe", "Staff Notes", "Notas del personal", "Investigate", "Investigar",
                "Cases", "Casos", "Protection", "Protección", "Recovery", "Recuperación", "Retention", "Retención",
                "Health", "Estado", "Recent logs", "Registros recientes", "Agentic proposals", "Propuestas del agente",
                "Provider key", "Clave del proveedor", "Owner consent", "Consentimiento del propietario",
                "Configuration", "Configuración", "Privacy boundary", "Límite de privacidad",
                "Save securely", "Guardar de forma segura", "Test key", "Probar clave",
                "Remove stored key", "Eliminar clave guardada", "Model", "Modelo", "Thinking level", "Nivel de razonamiento",
                "Output length", "Longitud de respuesta", "Temperature", "Temperatura", "Max output tokens", "Máximo de tokens de salida",
                "Requests per minute", "Solicitudes por minuto", "Requests per day", "Solicitudes por día",
                "Input tokens per minute", "Tokens de entrada por minuto", "Max provider calls", "Máximas llamadas al proveedor",
                "Enable provider calls after saving", "Activar llamadas al proveedor después de guardar",
                "Allow guarded agentic proposals", "Permitir propuestas de agente protegidas",
                "Online", "En línea", "Offline", "Desconectado", "Enabled", "Activado", "Disabled", "Desactivado",
                "Active", "Activo", "Ready", "Listo", "Pending", "Pendiente", "Open", "Abierto", "Closed", "Cerrado",
                "Resolved", "Resuelto", "Failed", "Fallido", "Healthy", "Saludable", "Degraded", "Degradado",
                "Operational", "Operativo", "Paused", "En pausa", "Live", "En directo", "Unknown", "Desconocido",
                "Search", "Buscar", "Filter", "Filtrar", "Previous", "Anterior", "Next", "Siguiente",
                "Export JSON", "Exportar JSON", "Export CSV", "Exportar CSV", "Clear", "Limpiar", "Close", "Cerrar",
                "Retry", "Reintentar", "Apply", "Aplicar", "Approve", "Aprobar", "Reject", "Rechazar",
                "Reason", "Motivo", "Status", "Estado", "Priority", "Prioridad", "Title", "Título",
                "Description", "Descripción", "Message", "Mensaje", "Name", "Nombre", "Type", "Tipo",
                "Created", "Creado", "Updated", "Actualizado", "Actions", "Acciones", "Details", "Detalles",
                "No data available", "No hay datos disponibles", "No results", "Sin resultados",
                "No notifications yet", "Aún no hay notificaciones", "No tickets found", "No se encontraron tickets",
                "Connection test failed", "La prueba de conexión falló", "Request timed out", "La solicitud agotó el tiempo",
                "Permission denied", "Permiso denegado", "Invalid request", "Solicitud no válida",
                "Changes saved", "Cambios guardados", "Copied to clipboard", "Copiado al portapapeles",
                "Server intelligence with guarded actions", "Inteligencia del servidor con acciones protegidas",
                "Analyze evidence, prepare changes and keep every mutation behind a human approval.",
                "Analice pruebas, prepare cambios y mantenga cada modificación tras una aprobación humana.",
                "Choose the display language for the admin panel. Saved to your user account.",
                "Elija el idioma de la interfaz del panel. Se guarda en su cuenta de usuario.");
        all.put("es", es);

        // --- Italian ---
        Map<String, String> it = new LinkedHashMap<>();
        putNav(it, "Dashboard", "Cruscotto", "Console", "Console", "Players", "Giocatori", "Files", "File",
                "Plugins", "Plugin", "Users", "Utenti", "Permissions", "Permessi", "Settings", "Impostazioni",
                "Audit Log", "Registro audit", "Tasks", "Attività", "Updates", "Aggiornamenti",
                "Plugin Settings", "Impostazioni plugin", "Servers", "Server", "Scan Servers", "Scansiona server",
                "Create Server", "Crea server", "Profile", "Profilo", "Menu", "Menu");
        putCommon(it, "Save", "Salva", "Save Settings", "Salva impostazioni", "Save Setting", "Salva impostazione",
                "Cancel", "Annulla", "Delete", "Elimina", "Edit", "Modifica", "Add", "Aggiungi", "Remove", "Rimuovi",
                "Create", "Crea", "Update", "Aggiorna", "Confirm", "Conferma", "Logout", "Esci",
                "Login", "Accedi", "Back", "Indietro", "Refresh", "Ricarica", "Stop", "Ferma",
                "Start", "Avvia", "Restart", "Riavvia", "Download", "Scarica", "Upload", "Carica",
                "Saving...", "Salvataggio...", "Loading...", "Caricamento...", "Error", "Errore",
                "Success", "Successo", "Warning", "Avviso", "Info", "Info",
                "Username", "Nome utente", "Password", "Password", "Role", "Ruolo", "Create User", "Crea utente",
                "Delete User", "Elimina utente", "Update Role", "Aggiorna ruolo", "Make Main-Admin", "Rendi admin principale",
                "Global Role", "Ruolo globale", "User & Role Management", "Gestione utenti e ruoli",
                "Server Uptime", "Uptime del server", "TPS", "TPS", "RAM Usage", "Uso RAM",
                "Language", "Lingua", "UI Language", "Lingua interfaccia",
                "General Settings", "Impostazioni generali", "Web Port", "Porta web", "Panel URL", "URL del pannello",
                "Max Backups", "Backup max", "NeoBridge Settings", "Impostazioni NeoBridge", "Bridge Enabled", "Bridge attivo",
                "Bridge Secret", "Segreto bridge", "NeoDash Master URL", "URL master NeoDash",
                "Discord Webhooks", "Webhook Discord", "Add Webhook", "Aggiungi webhook",
                "Audit Logs", "Registri audit", "Player Chat", "Chat giocatori", "Server Start/Stop", "Avvio/Arresto server",
                "Console Warnings", "Avvisi console",
                "Update Check Interval", "Intervallo controllo aggiornamenti", "Interval (minutes)", "Intervallo (minuti)",
                "SSO Enabled", "SSO attivo", "Global SSO Secret (optional)", "Segreto SSO globale (opzionale)",
                "Save SSO Settings", "Salva impostazioni SSO",
                "Destruction Zone", "Zona di distruzione", "Destroy", "Distruggi",
                "Settings saved successfully", "Impostazioni salvate con successo",
                "Failed to save settings", "Impossibile salvare le impostazioni",
                "Language preference saved.", "Preferenza lingua salvata.",
                "Are you sure?", "Sei sicuro?", "Yes", "Sì", "No", "No",
                "Choose the display language for the admin panel. Saved in your browser.",
                "Scegli la lingua di visualizzazione del pannello admin. Salvata nel tuo account.",
                "Select the display language for the NeoDash admin panel. Your preference is saved locally in this browser.",
                "Seleziona la lingua del pannello NeoDash. La tua preferenza è salvata nel tuo account.",
                "Global NeoDash configuration.", "Configurazione globale di NeoDash.");
        all.put("it", it);

        // --- Portuguese ---
        Map<String, String> pt = new LinkedHashMap<>();
        putNav(pt, "Dashboard", "Painel", "Console", "Console", "Players", "Jogadores", "Files", "Arquivos",
                "Plugins", "Plugins", "Users", "Usuários", "Permissions", "Permissões", "Settings", "Configurações",
                "Audit Log", "Registro de auditoria", "Tasks", "Tarefas", "Updates", "Atualizações",
                "Plugin Settings", "Configurações do plugin", "Servers", "Servidores", "Scan Servers", "Escanear servidores",
                "Create Server", "Criar servidor", "Profile", "Perfil", "Menu", "Menu");
        putCommon(pt, "Save", "Salvar", "Save Settings", "Salvar configurações", "Save Setting", "Salvar configuração",
                "Cancel", "Cancelar", "Delete", "Excluir", "Edit", "Editar", "Add", "Adicionar", "Remove", "Remover",
                "Create", "Criar", "Update", "Atualizar", "Confirm", "Confirmar", "Logout", "Sair",
                "Login", "Entrar", "Back", "Voltar", "Refresh", "Atualizar", "Stop", "Parar",
                "Start", "Iniciar", "Restart", "Reiniciar", "Download", "Baixar", "Upload", "Enviar",
                "Saving...", "Salvando...", "Loading...", "Carregando...", "Error", "Erro",
                "Success", "Sucesso", "Warning", "Aviso", "Info", "Info",
                "Username", "Usuário", "Password", "Senha", "Role", "Função", "Create User", "Criar usuário",
                "Delete User", "Excluir usuário", "Update Role", "Atualizar função", "Make Main-Admin", "Tornar admin principal",
                "Global Role", "Função global", "User & Role Management", "Gerenciamento de usuários e funções",
                "Server Uptime", "Tempo ativo", "TPS", "TPS", "RAM Usage", "Uso de RAM",
                "Language", "Idioma", "UI Language", "Idioma da interface",
                "General Settings", "Configurações gerais", "Web Port", "Porta web", "Panel URL", "URL do painel",
                "Max Backups", "Backups máx.", "NeoBridge Settings", "Configurações NeoBridge", "Bridge Enabled", "Bridge ativado",
                "Bridge Secret", "Segredo do bridge", "NeoDash Master URL", "URL mestre do NeoDash",
                "Discord Webhooks", "Webhooks do Discord", "Add Webhook", "Adicionar webhook",
                "Audit Logs", "Registros de auditoria", "Player Chat", "Chat de jogadores", "Server Start/Stop", "Início/Parada do servidor",
                "Console Warnings", "Avisos do console",
                "Update Check Interval", "Intervalo de verificação de atualizações", "Interval (minutes)", "Intervalo (minutos)",
                "SSO Enabled", "SSO ativado", "Global SSO Secret (optional)", "Segredo SSO global (opcional)",
                "Save SSO Settings", "Salvar configurações SSO",
                "Destruction Zone", "Zona de destruição", "Destroy", "Destruir",
                "Settings saved successfully", "Configurações salvas com sucesso",
                "Failed to save settings", "Falha ao salvar configurações",
                "Language preference saved.", "Preferência de idioma salva.",
                "Are you sure?", "Tem certeza?", "Yes", "Sim", "No", "Não",
                "Choose the display language for the admin panel. Saved in your browser.",
                "Escolha o idioma de exibição do painel admin. Salvo na sua conta.",
                "Select the display language for the NeoDash admin panel. Your preference is saved locally in this browser.",
                "Selecione o idioma de exibição do painel NeoDash. Sua preferência é salva na sua conta.",
                "Global NeoDash configuration.", "Configuração global do NeoDash.");
        all.put("pt", pt);

        // --- Dutch ---
        Map<String, String> nl = new LinkedHashMap<>();
        putNav(nl, "Dashboard", "Overzicht", "Console", "Console", "Players", "Spelers", "Files", "Bestanden",
                "Plugins", "Plug-ins", "Users", "Gebruikers", "Permissions", "Rechten", "Settings", "Instellingen",
                "Audit Log", "Auditlog", "Tasks", "Taken", "Updates", "Updates",
                "Plugin Settings", "Plug-in-instellingen", "Servers", "Servers", "Scan Servers", "Servers scannen",
                "Create Server", "Server maken", "Profile", "Profiel", "Menu", "Menu");
        putCommon(nl, "Save", "Opslaan", "Save Settings", "Instellingen opslaan", "Save Setting", "Instelling opslaan",
                "Cancel", "Annuleren", "Delete", "Verwijderen", "Edit", "Bewerken", "Add", "Toevoegen", "Remove", "Verwijderen",
                "Create", "Maken", "Update", "Bijwerken", "Confirm", "Bevestigen", "Logout", "Uitloggen",
                "Login", "Inloggen", "Back", "Terug", "Refresh", "Vernieuwen", "Stop", "Stoppen",
                "Start", "Starten", "Restart", "Opnieuw starten", "Download", "Downloaden", "Upload", "Uploaden",
                "Saving...", "Bezig met opslaan...", "Loading...", "Bezig met laden...", "Error", "Fout",
                "Success", "Succes", "Warning", "Waarschuwing", "Info", "Info",
                "Username", "Gebruikersnaam", "Password", "Wachtwoord", "Role", "Rol", "Create User", "Gebruiker maken",
                "Delete User", "Gebruiker verwijderen", "Update Role", "Rol bijwerken", "Make Main-Admin", "Hoofd-admin maken",
                "Global Role", "Globale rol", "User & Role Management", "Gebruikers- en rollenbeheer",
                "Server Uptime", "Server-uptime", "TPS", "TPS", "RAM Usage", "RAM-gebruik",
                "Language", "Taal", "UI Language", "Interface-taal",
                "General Settings", "Algemene instellingen", "Web Port", "Web-poort", "Panel URL", "Paneel-URL",
                "Max Backups", "Max. back-ups", "NeoBridge Settings", "NeoBridge-instellingen", "Bridge Enabled", "Bridge ingeschakeld",
                "Bridge Secret", "Bridge-geheim", "NeoDash Master URL", "NeoDash master-URL",
                "Discord Webhooks", "Discord-webhooks", "Add Webhook", "Webhook toevoegen",
                "Audit Logs", "Auditlogs", "Player Chat", "Spelerchat", "Server Start/Stop", "Server starten/stoppen",
                "Console Warnings", "Console-waarschuwingen",
                "Update Check Interval", "Update-controle-interval", "Interval (minutes)", "Interval (minuten)",
                "SSO Enabled", "SSO ingeschakeld", "Global SSO Secret (optional)", "Globaal SSO-geheim (optioneel)",
                "Save SSO Settings", "SSO-instellingen opslaan",
                "Destruction Zone", "Vernietigingszone", "Destroy", "Vernietigen",
                "Settings saved successfully", "Instellingen opgeslagen",
                "Failed to save settings", "Kan instellingen niet opslaan",
                "Language preference saved.", "Taalvoorkeur opgeslagen.",
                "Are you sure?", "Weet je het zeker?", "Yes", "Ja", "No", "Nee",
                "Choose the display language for the admin panel. Saved in your browser.",
                "Kies de weergavetaal voor het admin-paneel. Opgeslagen in uw account.",
                "Select the display language for the NeoDash admin panel. Your preference is saved locally in this browser.",
                "Selecteer de weergavetaal voor het NeoDash-paneel. Uw voorkeur wordt in uw account opgeslagen.",
                "Global NeoDash configuration.", "Globale NeoDash-configuratie.");
        all.put("nl", nl);

        // --- Polish ---
        Map<String, String> pl = new LinkedHashMap<>();
        putNav(pl, "Dashboard", "Panel", "Console", "Konsola", "Players", "Gracze", "Files", "Pliki",
                "Plugins", "Wtyczki", "Users", "Użytkownicy", "Permissions", "Uprawnienia", "Settings", "Ustawienia",
                "Audit Log", "Dziennik audytu", "Tasks", "Zadania", "Updates", "Aktualizacje",
                "Plugin Settings", "Ustawienia wtyczki", "Servers", "Serwery", "Scan Servers", "Skanuj serwery",
                "Create Server", "Utwórz serwer", "Profile", "Profil", "Menu", "Menu");
        putCommon(pl, "Save", "Zapisz", "Save Settings", "Zapisz ustawienia", "Save Setting", "Zapisz ustawienie",
                "Cancel", "Anuluj", "Delete", "Usuń", "Edit", "Edytuj", "Add", "Dodaj", "Remove", "Usuń",
                "Create", "Utwórz", "Update", "Aktualizuj", "Confirm", "Potwierdź", "Logout", "Wyloguj",
                "Login", "Zaloguj", "Back", "Wstecz", "Refresh", "Odśwież", "Stop", "Zatrzymaj",
                "Start", "Uruchom", "Restart", "Uruchom ponownie", "Download", "Pobierz", "Upload", "Wyślij",
                "Saving...", "Zapisywanie...", "Loading...", "Ładowanie...", "Error", "Błąd",
                "Success", "Sukces", "Warning", "Ostrzeżenie", "Info", "Info",
                "Username", "Nazwa użytkownika", "Password", "Hasło", "Role", "Rola", "Create User", "Utwórz użytkownika",
                "Delete User", "Usuń użytkownika", "Update Role", "Aktualizuj rolę", "Make Main-Admin", "Ustaw jako głównego administratora",
                "Global Role", "Rola globalna", "User & Role Management", "Zarządzanie użytkownikami i rolami",
                "Server Uptime", "Czas działania serwera", "TPS", "TPS", "RAM Usage", "Użycie RAM",
                "Language", "Język", "UI Language", "Język interfejsu",
                "General Settings", "Ustawienia ogólne", "Web Port", "Port web", "Panel URL", "URL panelu",
                "Max Backups", "Maks. kopii zapasowych", "NeoBridge Settings", "Ustawienia NeoBridge", "Bridge Enabled", "Bridge włączony",
                "Bridge Secret", "Sekret bridge'a", "NeoDash Master URL", "Główny URL NeoDash",
                "Discord Webhooks", "Webhooki Discord", "Add Webhook", "Dodaj webhook",
                "Audit Logs", "Dzienniki audytu", "Player Chat", "Czat graczy", "Server Start/Stop", "Start/Stop serwera",
                "Console Warnings", "Ostrzeżenia konsoli",
                "Update Check Interval", "Interwał sprawdzania aktualizacji", "Interval (minutes)", "Interwał (minuty)",
                "SSO Enabled", "SSO włączone", "Global SSO Secret (optional)", "Globalny sekret SSO (opcjonalny)",
                "Save SSO Settings", "Zapisz ustawienia SSO",
                "Destruction Zone", "Strefa zniszczenia", "Destroy", "Zniszcz",
                "Settings saved successfully", "Ustawienia zapisane pomyślnie",
                "Failed to save settings", "Nie udało się zapisać ustawień",
                "Language preference saved.", "Preferencja językowa zapisana.",
                "Are you sure?", "Czy na pewno?", "Yes", "Tak", "No", "Nie",
                "Choose the display language for the admin panel. Saved in your browser.",
                "Wybierz język panelu administratora. Zapisany w Twoim koncie.",
                "Select the display language for the NeoDash admin panel. Your preference is saved locally in this browser.",
                "Wybierz język panelu NeoDash. Preferencja jest zapisana w Twoim koncie.",
                "Global NeoDash configuration.", "Globalna konfiguracja NeoDash.");
        all.put("pl", pl);

        // --- Russian ---
        Map<String, String> ru = new LinkedHashMap<>();
        putNav(ru, "Dashboard", "Панель", "Console", "Консоль", "Players", "Игроки", "Files", "Файлы",
                "Plugins", "Плагины", "Users", "Пользователи", "Permissions", "Права", "Settings", "Настройки",
                "Audit Log", "Журнал аудита", "Tasks", "Задачи", "Updates", "Обновления",
                "Plugin Settings", "Настройки плагина", "Servers", "Серверы", "Scan Servers", "Сканировать серверы",
                "Create Server", "Создать сервер", "Profile", "Профиль", "Menu", "Меню");
        putCommon(ru, "Save", "Сохранить", "Save Settings", "Сохранить настройки", "Save Setting", "Сохранить",
                "Cancel", "Отмена", "Delete", "Удалить", "Edit", "Изменить", "Add", "Добавить", "Remove", "Удалить",
                "Create", "Создать", "Update", "Обновить", "Confirm", "Подтвердить", "Logout", "Выйти",
                "Login", "Войти", "Back", "Назад", "Refresh", "Обновить", "Stop", "Остановить",
                "Start", "Запустить", "Restart", "Перезапустить", "Download", "Скачать", "Upload", "Загрузить",
                "Saving...", "Сохранение...", "Loading...", "Загрузка...", "Error", "Ошибка",
                "Success", "Успех", "Warning", "Предупреждение", "Info", "Информация",
                "Username", "Имя пользователя", "Password", "Пароль", "Role", "Роль", "Create User", "Создать пользователя",
                "Delete User", "Удалить пользователя", "Update Role", "Обновить роль", "Make Main-Admin", "Назначить главным админом",
                "Global Role", "Глобальная роль", "User & Role Management", "Управление пользователями и ролями",
                "Server Uptime", "Время работы сервера", "TPS", "TPS", "RAM Usage", "Использование ОЗУ",
                "Language", "Язык", "UI Language", "Язык интерфейса",
                "General Settings", "Общие настройки", "Web Port", "Веб-порт", "Panel URL", "URL панели",
                "Max Backups", "Макс. резервных копий", "NeoBridge Settings", "Настройки NeoBridge", "Bridge Enabled", "Bridge включён",
                "Bridge Secret", "Секрет моста", "NeoDash Master URL", "Мастер URL NeoDash",
                "Discord Webhooks", "Вебхуки Discord", "Add Webhook", "Добавить вебхук",
                "Audit Logs", "Журналы аудита", "Player Chat", "Чат игроков", "Server Start/Stop", "Запуск/Остановка сервера",
                "Console Warnings", "Предупреждения консоли",
                "Update Check Interval", "Интервал проверки обновлений", "Interval (minutes)", "Интервал (минуты)",
                "SSO Enabled", "SSO включён", "Global SSO Secret (optional)", "Глобальный SSO-секрет (опционально)",
                "Save SSO Settings", "Сохранить настройки SSO",
                "Destruction Zone", "Зона уничтожения", "Destroy", "Уничтожить",
                "Settings saved successfully", "Настройки успешно сохранены",
                "Failed to save settings", "Не удалось сохранить настройки",
                "Language preference saved.", "Языковая настройка сохранена.",
                "Are you sure?", "Вы уверены?", "Yes", "Да", "No", "Нет",
                "Choose the display language for the admin panel. Saved in your browser.",
                "Выберите язык отображения панели администратора. Сохраняется в вашей учётной записи.",
                "Select the display language for the NeoDash admin panel. Your preference is saved locally in this browser.",
                "Выберите язык панели NeoDash. Настройка сохраняется в вашей учётной записи.",
                "Global NeoDash configuration.", "Глобальная конфигурация NeoDash.");
        all.put("ru", ru);

        // --- Turkish ---
        Map<String, String> tr = new LinkedHashMap<>();
        putNav(tr, "Dashboard", "Kontrol Paneli", "Console", "Konsol", "Players", "Oyuncular", "Files", "Dosyalar",
                "Plugins", "Eklentiler", "Users", "Kullanıcılar", "Permissions", "İzinler", "Settings", "Ayarlar",
                "Audit Log", "Denetim Kaydı", "Tasks", "Görevler", "Updates", "Güncellemeler",
                "Plugin Settings", "Eklenti Ayarları", "Servers", "Sunucular", "Scan Servers", "Sunucuları Tara",
                "Create Server", "Sunucu Oluştur", "Profile", "Profil", "Menu", "Menü");
        putCommon(tr, "Save", "Kaydet", "Save Settings", "Ayarları Kaydet", "Save Setting", "Ayarı Kaydet",
                "Cancel", "İptal", "Delete", "Sil", "Edit", "Düzenle", "Add", "Ekle", "Remove", "Kaldır",
                "Create", "Oluştur", "Update", "Güncelle", "Confirm", "Onayla", "Logout", "Çıkış",
                "Login", "Giriş", "Back", "Geri", "Refresh", "Yenile", "Stop", "Durdur",
                "Start", "Başlat", "Restart", "Yeniden Başlat", "Download", "İndir", "Upload", "Yükle",
                "Saving...", "Kaydediliyor...", "Loading...", "Yükleniyor...", "Error", "Hata",
                "Success", "Başarılı", "Warning", "Uyarı", "Info", "Bilgi",
                "Username", "Kullanıcı adı", "Password", "Parola", "Role", "Rol", "Create User", "Kullanıcı Oluştur",
                "Delete User", "Kullanıcıyı Sil", "Update Role", "Rolü Güncelle", "Make Main-Admin", "Ana yöneticiye yükselt",
                "Global Role", "Genel Rol", "User & Role Management", "Kullanıcı ve Rol Yönetimi",
                "Server Uptime", "Sunucu Çalışma Süresi", "TPS", "TPS", "RAM Usage", "RAM Kullanımı",
                "Language", "Dil", "UI Language", "Arayüz Dili",
                "General Settings", "Genel Ayarlar", "Web Port", "Web Portu", "Panel URL", "Panel URL'si",
                "Max Backups", "Maks. Yedek", "NeoBridge Settings", "NeoBridge Ayarları", "Bridge Enabled", "Bridge etkin",
                "Bridge Secret", "Bridge Sırrı", "NeoDash Master URL", "NeoDash Ana URL",
                "Discord Webhooks", "Discord Webhook'ları", "Add Webhook", "Webhook Ekle",
                "Audit Logs", "Denetim Kayıtları", "Player Chat", "Oyuncu Sohbeti", "Server Start/Stop", "Sunucu Başlat/Durdur",
                "Console Warnings", "Konsol Uyarıları",
                "Update Check Interval", "Güncelleme Kontrol Aralığı", "Interval (minutes)", "Aralık (dakika)",
                "SSO Enabled", "SSO Etkin", "Global SSO Secret (optional)", "Genel SSO Sırrı (isteğe bağlı)",
                "Save SSO Settings", "SSO Ayarlarını Kaydet",
                "Destruction Zone", "İmha Bölgesi", "Destroy", "Yok Et",
                "Settings saved successfully", "Ayarlar başarıyla kaydedildi",
                "Failed to save settings", "Ayarlar kaydedilemedi",
                "Language preference saved.", "Dil tercihi kaydedildi.",
                "Are you sure?", "Emin misiniz?", "Yes", "Evet", "No", "Hayır",
                "Choose the display language for the admin panel. Saved in your browser.",
                "Yönetim panelinin görüntüleme dilini seçin. Hesabınıza kaydedilir.",
                "Select the display language for the NeoDash admin panel. Your preference is saved locally in this browser.",
                "NeoDash yönetim panelinin dilini seçin. Tercihiniz hesabınıza kaydedilir.",
                "Global NeoDash configuration.", "Genel NeoDash yapılandırması.");
        all.put("tr", tr);

        // --- Chinese Simplified ---
        Map<String, String> zh = new LinkedHashMap<>();
        putNav(zh, "Dashboard", "仪表板", "Console", "控制台", "Players", "玩家", "Files", "文件",
                "Plugins", "插件", "Users", "用户", "Permissions", "权限", "Settings", "设置",
                "Audit Log", "审计日志", "Tasks", "任务", "Updates", "更新",
                "Plugin Settings", "插件设置", "Servers", "服务器", "Scan Servers", "扫描服务器",
                "Create Server", "创建服务器", "Profile", "个人资料", "Menu", "菜单");
        putCommon(zh, "Save", "保存", "Save Settings", "保存设置", "Save Setting", "保存设置",
                "Cancel", "取消", "Delete", "删除", "Edit", "编辑", "Add", "添加", "Remove", "移除",
                "Create", "创建", "Update", "更新", "Confirm", "确认", "Logout", "退出",
                "Login", "登录", "Back", "返回", "Refresh", "刷新", "Stop", "停止",
                "Start", "启动", "Restart", "重启", "Download", "下载", "Upload", "上传",
                "Saving...", "保存中...", "Loading...", "加载中...", "Error", "错误",
                "Success", "成功", "Warning", "警告", "Info", "信息",
                "Username", "用户名", "Password", "密码", "Role", "角色", "Create User", "创建用户",
                "Delete User", "删除用户", "Update Role", "更新角色", "Make Main-Admin", "设为主管理员",
                "Global Role", "全局角色", "User & Role Management", "用户和角色管理",
                "Server Uptime", "服务器运行时间", "TPS", "TPS", "RAM Usage", "内存使用",
                "Language", "语言", "UI Language", "界面语言",
                "General Settings", "常规设置", "Web Port", "Web 端口", "Panel URL", "面板 URL",
                "Max Backups", "最大备份数", "NeoBridge Settings", "NeoBridge 设置", "Bridge Enabled", "启用 Bridge",
                "Bridge Secret", "Bridge 密钥", "NeoDash Master URL", "NeoDash 主 URL",
                "Discord Webhooks", "Discord Webhook", "Add Webhook", "添加 Webhook",
                "Audit Logs", "审计日志", "Player Chat", "玩家聊天", "Server Start/Stop", "服务器启动/停止",
                "Console Warnings", "控制台警告",
                "Update Check Interval", "更新检查间隔", "Interval (minutes)", "间隔（分钟）",
                "SSO Enabled", "启用 SSO", "Global SSO Secret (optional)", "全局 SSO 密钥（可选）",
                "Save SSO Settings", "保存 SSO 设置",
                "Destruction Zone", "销毁区域", "Destroy", "销毁",
                "Settings saved successfully", "设置保存成功",
                "Failed to save settings", "无法保存设置",
                "Language preference saved.", "语言偏好已保存。",
                "Are you sure?", "您确定吗？", "Yes", "是", "No", "否",
                "Choose the display language for the admin panel. Saved in your browser.",
                "选择管理面板的显示语言。保存在您的账户中。",
                "Select the display language for the NeoDash admin panel. Your preference is saved locally in this browser.",
                "选择 NeoDash 面板的显示语言。您的偏好保存在您的账户中。",
                "Global NeoDash configuration.", "NeoDash 全局配置。");
        all.put("zh", zh);

        // --- Japanese ---
        Map<String, String> ja = new LinkedHashMap<>();
        putNav(ja, "Dashboard", "ダッシュボード", "Console", "コンソール", "Players", "プレイヤー", "Files", "ファイル",
                "Plugins", "プラグイン", "Users", "ユーザー", "Permissions", "権限", "Settings", "設定",
                "Audit Log", "監査ログ", "Tasks", "タスク", "Updates", "更新",
                "Plugin Settings", "プラグイン設定", "Servers", "サーバー", "Scan Servers", "サーバーをスキャン",
                "Create Server", "サーバーを作成", "Profile", "プロフィール", "Menu", "メニュー");
        putCommon(ja, "Save", "保存", "Save Settings", "設定を保存", "Save Setting", "設定を保存",
                "Cancel", "キャンセル", "Delete", "削除", "Edit", "編集", "Add", "追加", "Remove", "削除",
                "Create", "作成", "Update", "更新", "Confirm", "確認", "Logout", "ログアウト",
                "Login", "ログイン", "Back", "戻る", "Refresh", "更新", "Stop", "停止",
                "Start", "開始", "Restart", "再起動", "Download", "ダウンロード", "Upload", "アップロード",
                "Saving...", "保存中...", "Loading...", "読み込み中...", "Error", "エラー",
                "Success", "成功", "Warning", "警告", "Info", "情報",
                "Username", "ユーザー名", "Password", "パスワード", "Role", "ロール", "Create User", "ユーザーを作成",
                "Delete User", "ユーザーを削除", "Update Role", "ロールを更新", "Make Main-Admin", "メイン管理者にする",
                "Global Role", "グローバルロール", "User & Role Management", "ユーザーとロールの管理",
                "Server Uptime", "サーバー稼働時間", "TPS", "TPS", "RAM Usage", "RAM使用量",
                "Language", "言語", "UI Language", "UI言語",
                "General Settings", "一般設定", "Web Port", "Webポート", "Panel URL", "パネルURL",
                "Max Backups", "最大バックアップ数", "NeoBridge Settings", "NeoBridge 設定", "Bridge Enabled", "Bridge 有効",
                "Bridge Secret", "Bridge シークレット", "NeoDash Master URL", "NeoDash マスターURL",
                "Discord Webhooks", "Discord Webhook", "Add Webhook", "Webhookを追加",
                "Audit Logs", "監査ログ", "Player Chat", "プレイヤーチャット", "Server Start/Stop", "サーバーの起動/停止",
                "Console Warnings", "コンソール警告",
                "Update Check Interval", "更新チェック間隔", "Interval (minutes)", "間隔（分）",
                "SSO Enabled", "SSO 有効", "Global SSO Secret (optional)", "グローバルSSOシークレット（任意）",
                "Save SSO Settings", "SSO設定を保存",
                "Destruction Zone", "破壊ゾーン", "Destroy", "破壊",
                "Settings saved successfully", "設定を保存しました",
                "Failed to save settings", "設定の保存に失敗しました",
                "Language preference saved.", "言語設定を保存しました。",
                "Are you sure?", "よろしいですか？", "Yes", "はい", "No", "いいえ",
                "Choose the display language for the admin panel. Saved in your browser.",
                "管理パネルの表示言語を選択してください。アカウントに保存されます。",
                "Select the display language for the NeoDash admin panel. Your preference is saved locally in this browser.",
                "NeoDash パネルの表示言語を選択してください。設定はアカウントに保存されます。",
                "Global NeoDash configuration.", "NeoDash のグローバル設定。");
        all.put("ja", ja);

        return Collections.unmodifiableMap(all);
    }

    private static void putPairs(Map<String, String> out, String... pairs) {
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            out.put(pairs[i], pairs[i + 1]);
        }
    }

    private static void putNav(Map<String, String> out, String... pairs) {
        putPairs(out, pairs);
    }

    private static void putCommon(Map<String, String> out, String... pairs) {
        putPairs(out, pairs);
    }

    /**
     * DOM-walking translator that runs on DOMContentLoaded, observes mutations
     * for dynamically inserted content, and translates on SPA navigation as
     * well. Skips &lt;script&gt;, &lt;style&gt;, and elements marked with
     * data-i18n-skip.
     */
    private static final String TRANSLATOR_JS = ""
            + "(function(){\n"
            + "  var lang=window.__dashLang||'en';\n"
            + "  var dict=window.__dashI18n||{};\n"
            + "  if(lang==='en')return;\n"
            + "  function tr(s){if(typeof s!=='string')return s;var k=s.trim();if(!k)return s;var v=dict[k];if(v==null)return s;var pre=s.length-s.replace(/^\\s+/,'').length;var post=s.length-s.replace(/\\s+$/,'').length;return s.substring(0,pre)+v+s.substring(s.length-post);}\n"
            + "  var SKIP=new Set(['SCRIPT','STYLE','NOSCRIPT','CODE','PRE','TEXTAREA']);\n"
            + "  function walk(node){\n"
            + "    if(!node)return;\n"
            + "    if(node.nodeType===3){var p=node.parentNode;if(p&&!SKIP.has(p.nodeName)&&!p.closest('[data-i18n-skip]')){var t=tr(node.nodeValue);if(t!==node.nodeValue)node.nodeValue=t;}return;}\n"
            + "    if(node.nodeType!==1)return;\n"
            + "    if(SKIP.has(node.nodeName))return;\n"
            + "    if(node.hasAttribute && node.hasAttribute('data-i18n-skip'))return;\n"
            + "    ['placeholder','title','aria-label'].forEach(function(a){if(node.hasAttribute&&node.hasAttribute(a)){var nv=tr(node.getAttribute(a));if(nv!==node.getAttribute(a))node.setAttribute(a,nv);}});\n"
            + "    if(node.nodeName==='INPUT'){var t=node.type;if(t==='button'||t==='submit'||t==='reset'){if(node.value){var nv=tr(node.value);if(nv!==node.value)node.value=nv;}}}\n"
            + "    var c=node.childNodes;for(var i=0;i<c.length;i++)walk(c[i]);\n"
            + "  }\n"
            + "  function run(){walk(document.body);}\n"
            + "  if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',run);else run();\n"
            + "  var obs=new MutationObserver(function(muts){muts.forEach(function(m){m.addedNodes.forEach(function(n){walk(n);});});});\n"
            + "  try{obs.observe(document.documentElement||document.body,{childList:true,subtree:true});}catch(e){}\n"
            + "  window.__dashTranslate=run;\n"
            + "})();\n";
}
