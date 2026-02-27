# SAV.e Copilot

CLI Java che permette di interrogare l'agente GitHub Copilot con accesso al database SAV.e tramite DocMind.

## Requisiti

- Java 17+
- Maven 3.8+
- GitHub Copilot CLI (`copilot`) installato e autenticato
- DocMind in esecuzione su `localhost:8008` con il progetto `save`

## Configurazione

Copia il file `.env.example` in `.env` e verifica i parametri:

```bash
cp .env.example .env
```

| Variabile | Descrizione | Default |
|-----------|-------------|---------|
| `COPILOT_CMD` | Percorso del binario `copilot` | `copilot` |
| `COPILOT_EXTRA_ARGS` | Argomenti aggiuntivi per copilot | `--allow-all` |
| `DOCMIND_PROJECT` | Progetto DocMind | `save` |
| `DOCMIND_DOCUMENT` | Documento schema DB | `save-iso-prod-db-schema` |
| `SYSTEM_PROMPT_EXTRA` | Prompt di sistema aggiuntivo | _(vuoto)_ |

## Build

```bash
mvn package -q
```

## Esecuzione

```bash
java -jar target/save-copilot-1.0.0.jar
```

## Utilizzo

All'avvio l'agente carica automaticamente le linee guida del database (`save-iso-prod-db-schema`) da DocMind. Puoi poi fare domande in linguaggio naturale:

```
Tu> quanti checkin sono stati fatti nel mese di gennaio nella sede di torino
Tu> chi ha fatto più checkin nel 2025?
Tu> elenca le prenotazioni di oggi
```

Premi **Ctrl+C** per uscire.

## Architettura

```
Main.java              # Entry point, prompt loop
CopilotAcpClient.java  # Comunicazione JSON-RPC con copilot --acp
Config.java            # Lettura configurazione da .env
```

L'app comunica con `copilot --acp` tramite il protocollo **Agent Client Protocol (ACP)** basato su JSON-RPC 2.0 su stdin/stdout. Copilot ha già configurato DocMind come MCP server e vi accede automaticamente.
