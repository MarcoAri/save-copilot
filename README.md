# SAV.e Copilot

Applicazione Java da riga di comando che consente di interrogare un **agente GitHub Copilot** dotato di accesso al database SAV.e. L'agente carica automaticamente all'avvio le linee guida dello schema del database dal documento DocMind `save-iso-prod-db-schema`, consentendo di fare domande in linguaggio naturale che vengono tradotte in query SQL.

La comunicazione con Copilot avviene tramite il protocollo **Agent Client Protocol (ACP)** — un'interfaccia JSON-RPC 2.0 su stdin/stdout esposta dal binario `copilot --acp`.

---

## Requisiti

| Dipendenza | Versione minima | Note |
|---|---|---|
| Java | 17 | Richiede record e switch expression |
| Maven | 3.8 | Per il build |
| GitHub Copilot CLI | qualsiasi | Deve essere autenticato (`copilot login`) |
| DocMind | — | Deve girare su `localhost:8008` con il progetto `save` |

---

## Configurazione

```bash
cp .env.example .env
# Editare .env se necessario
```

| Variabile | Descrizione | Default |
|---|---|---|
| `COPILOT_CMD` | Percorso del binario `copilot` | `copilot` |
| `COPILOT_EXTRA_ARGS` | Flag aggiuntivi passati al processo copilot | `--allow-all` |
| `DOCMIND_PROJECT` | Progetto DocMind da cui caricare il contesto | `save` |
| `DOCMIND_DOCUMENT` | `uniqueName` del documento schema DB | `save-iso-prod-db-schema` |
| `SYSTEM_PROMPT_EXTRA` | Istruzioni aggiuntive per l'agente (opzionale) | _(vuoto)_ |

> **Nota:** I server MCP (incluso DocMind) sono letti automaticamente da `~/.copilot/mcp-config.json`. Non è necessario configurarli nel `.env`.

---

## Build

```bash
mvn package -q
# Produce: target/save-copilot-1.0.0.jar (fat jar, tutte le dipendenze incluse)
```

---

## Esecuzione

```bash
java -jar target/save-copilot-1.0.0.jar
```

All'avvio vengono eseguiti automaticamente:
1. **Handshake ACP** con il processo `copilot --acp`
2. **Creazione sessione** (`session/new`)
3. **Priming** dell'agente con il system prompt → l'agente richiama il tool DocMind `docmind-getFlavorByName` per caricare lo schema del database

Dopodiché il prompt è pronto. Premi **Ctrl+C** per uscire.

---

## Esempi di utilizzo

```
Tu> quanti checkin sono stati fatti nel mese di gennaio nella sede di torino
Tu> chi ha fatto più checkin nel 2025?
Tu> elenca le prenotazioni di questa settimana a Milano
Tu> quali zone sono disponibili per la prenotazione a Torino?
```

L'agente genera la query SQL corretta seguendo le convenzioni dello schema (timestamp in ms Unix, filtri per plant reali, ecc.) e, se il database è raggiungibile via DBMind, restituisce anche il risultato.

---

## Architettura

```
save-copilot/
├── pom.xml
├── .env                          ← parametri di configurazione (non in git)
├── .env.example                  ← template
└── src/main/java/com/savecopilot/
    ├── Main.java                 ← entry point: banner, init, prompt loop (Ctrl+C)
    ├── CopilotAcpClient.java     ← client ACP: gestione processo, JSON-RPC, streaming
    └── Config.java               ← lettura .env, costruzione system prompt
```

### Flusso di esecuzione

```
┌─────────────┐   spawn copilot --acp   ┌──────────────────────┐
│   Main.java  │ ──────────────────────► │  copilot (processo)   │
│             │                          │  + MCP servers:       │
│  prompt loop │ ◄── session/update ──── │    - docmind          │
│  (Ctrl+C)   │ ──── session/prompt ──► │    - dbmind           │
└─────────────┘                          │    - github           │
       ▲                                 └──────────────────────┘
       │ legge .env
  Config.java
```

### Protocollo ACP (riepilogo)

| Passo | Metodo JSON-RPC | Direzione | Descrizione |
|---|---|---|---|
| 1 | `initialize` | client → server | Handshake con `protocolVersion: 1` |
| 2 | `session/new` | client → server | Crea nuova sessione, ritorna `sessionId` |
| 3 | `session/prompt` | client → server | Invia messaggio, `prompt` è array `[{type,text}]` |
| — | `session/update` | server → client | Streaming chunk: `update.sessionUpdate = "agent_message_chunk"` |
| — | risposta `session/prompt` | server → client | `{stopReason: "end_turn"}` quando la risposta è completa |

Per la documentazione tecnica completa del protocollo ACP, vedi il documento **[copilot-acp-integration-guide](http://localhost:8008)** nel progetto DocMind `copilot-acp`.

---

## Dipendenze Maven

| Libreria | Versione | Utilizzo |
|---|---|---|
| `com.google.code.gson:gson` | 2.10.1 | Serializzazione/deserializzazione JSON-RPC |
| `io.github.cdimascio:dotenv-java` | 3.0.0 | Lettura file `.env` |

---

## Sviluppo e debug

Per abilitare il log di stderr di copilot (utile per debug), decommenta in `CopilotAcpClient.java`:

```java
// System.err.println("[copilot] " + line);
```

Per testare una singola domanda senza prompt interattivo:

```bash
echo "quanti checkin a torino in gennaio" | java -jar target/save-copilot-1.0.0.jar
```
