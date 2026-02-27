package com.savecopilot;

import io.github.cdimascio.dotenv.Dotenv;

public class Config {

    private final String copilotCmd;
    private final String copilotExtraArgs;
    private final String docmindProject;
    private final String docmindDocument;
    private final String systemPromptExtra;

    public Config() {
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
        this.copilotCmd       = dotenv.get("COPILOT_CMD", "copilot");
        this.copilotExtraArgs = dotenv.get("COPILOT_EXTRA_ARGS", "--allow-all");
        this.docmindProject   = dotenv.get("DOCMIND_PROJECT", "save");
        this.docmindDocument  = dotenv.get("DOCMIND_DOCUMENT", "save-iso-prod-db-schema");
        this.systemPromptExtra = dotenv.get("SYSTEM_PROMPT_EXTRA", "");
    }

    public String getCopilotCmd() { return copilotCmd; }
    public String getCopilotExtraArgs() { return copilotExtraArgs; }
    public String getDocmindProject() { return docmindProject; }
    public String getDocmindDocument() { return docmindDocument; }
    public String getSystemPromptExtra() { return systemPromptExtra; }

    /** System prompt injected at session creation */
    public String buildSystemPrompt() {
        String base = String.format(
            "Sei un assistente specializzato per il sistema SAV.e. " +
            "All'inizio della sessione, usa lo strumento docmind-getFlavorByName con uniqueName='%s' " +
            "per caricare le linee guida dello schema del database. " +
            "Usa queste linee guida per rispondere a tutte le domande sui dati del database. " +
            "Quando l'utente chiede informazioni sui dati, costruisci la query SQL appropriata " +
            "seguendo le convenzioni dello schema e fornisci sia la query che il risultato. " +
            "Rispondi sempre in italiano.",
            docmindDocument
        );
        if (systemPromptExtra != null && !systemPromptExtra.isBlank()) {
            base += "\n\n" + systemPromptExtra;
        }
        return base;
    }
}
