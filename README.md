# Solari Support Ticket Similarity Assistant (Java 17)

End-to-end implementation of the **Solari Analytics Support Ticket Similarity Assistant** in modern **Java 17**. When a new support ticket arrives, the assistant searches resolved historical tickets using a calibrated **Field-Weighted Query-Normalized BM25F + Character 3-Gram** hybrid engine, extracts actionable resolution steps, warns on symptom-stage discrepancies, cleanly flags cold-start/novel issues, and provides both a **CLI** and an interactive **Web Triage Console + REST API**.

See [`SOLUTION_DESIGN.md`](./SOLUTION_DESIGN.md) for full FDE problem framing, explicit scale/latency assumptions, architectural trade-offs, and the v2 production roadmap.

---

## Quickstart (Visual Studio Code & Single-Line Commands)

**Prerequisite:** **Java 17+** (`javac` and `java` on your `PATH`). No external JAR downloads, database setup, or API keys are required.

### Option A: Single-Line Command in VS Code Terminal
Open the `support-ticket-similarity` folder in **Visual Studio Code** (`File -> Open Folder`), open the Integrated Terminal (`` Ctrl+` ``), and run:

- **macOS / Linux / Git Bash (Start Web UI on `http://localhost:8080`):**
  ```bash
  javac --release 17 -encoding UTF-8 -sourcepath src/main/java -d out/main src/main/java/com/solari/similarity/Main.java && java -cp out/main com.solari.similarity.Main --serve --port 8080
  ```
  *(Or shorter via helper script: `bash run.sh server` | `bash run.sh cli` | `bash run.sh test`)*

- **Windows PowerShell / CMD (Start Web UI on `http://localhost:8080`):**
  ```powershell
  .\run.bat server --port 8080
  ```
  *(Or pure `javac`/`java` 1-liner in PowerShell: `javac --release 17 -encoding UTF-8 -sourcepath src/main/java -d out/main src/main/java/com/solari/similarity/Main.java; if ($?) { java -cp out/main com.solari.similarity.Main --serve --port 8080 }`)*

### Option B: Press `F5` in VS Code
Pre-configured `.vscode/launch.json` and `.vscode/tasks.json` are included:
1. Open the `support-ticket-similarity` folder in VS Code (with the *Extension Pack for Java* installed).
2. Press **`F5`** (select **`Solari: Start Web UI Server (http://localhost:8080)`**) or press `Ctrl+Shift+B` to build and launch directly.

### Option C: Maven Commands (Optional)
```bash
mvn test
mvn compile exec:java -Dexec.args="cli"
mvn compile exec:java -Dexec.args="--serve --port 8080"
```

---

## CLI Usage Examples

### Batch Evaluation (`NT-1` through `NT-4`)
```bash
./run.sh cli
```
Expected behavior across the four provided starter test cases:
- **`NT-1`** (*Password reset email never arrives*): Surfaces `T-1001` as **`RELATED` (63.8%)** with an explicit **Symptom Stage Caveat** warning the agent that `NT-1` involves non-delivery of the reset email whereas `T-1001` occurred *after* receiving the email.
- **`NT-2`** (*Chart shows nothing for the revenue tab*): Surfaces `T-1201` as **`STRONG` (52.1%)**, linking `"nothing"`/`"empty"` to `"blank"` and extracting empty-filter / regression guidance.
- **`NT-3`** (*Getting charged twice this month*): Surfaces `T-1303` as **`STRONG` (71.3%)**, highlighting duplicate-charge refund remediation + idempotency regression check.
- **`NT-4`** (*Snowflake data warehouse integration failing*): Triggers **`NONE` (`No Strong Historical Match`)**, explicitly flagging unseen subject entities (`warehouse`, `snowflake`) while offering low-confidence `sync` playbooks (`T-1105`, `T-1101`) strictly as general category reference.

### Triage a Custom Ticket (Human or JSON Output)
```bash
./run.sh --subject "Okta SSO login redirects to white screen" \
         --description "After signing in through Okta SAML, user lands on an empty page"

# Machine-readable JSON output
./run.sh --subject "Getting billed twice" --description "Duplicate monthly charge on card" --json
```

### Optional AI Bonus Track (Gemini or OpenAI)
If you set `GEMINI_API_KEY` or `OPENAI_API_KEY`, pass `--ai` (or check **AI Advisor** in the Web UI) to enable hybrid dense-embedding reranking (`text-embedding-004` / `text-embedding-3-small`) and a 1-sentence LLM triage synthesis note. If unset or unreachable, the engine runs 100% deterministically offline.

```bash
GEMINI_API_KEY="your-key" ./run.sh cli --ai
```

---

## Interactive Web UI & REST API (`./run.sh server`)

Start the server (`./run.sh server`) and open **`http://localhost:8080`**:
- **1-Click Preset Pills (`NT-1`–`NT-4`)** to walk through exact matches, partial/stage-mismatch warnings, and cold-start handling.
- **Close the Cold-Start Loop Live:** Expand **"+ Resolve & Add Novel Ticket to Knowledge Base"**, add a verified resolution for Snowflake (`NT-4`), click **Save & Index Resolution**, and immediately watch Snowflake tickets match at `STRONG` confidence (persisted to `data/runtime_tickets.json` without mutating seed `data/tickets.json`).
- **Agent Relevance Feedback:** 👍 / 👎 buttons record helpfulness signals to `data/feedback_log.json`.

### REST Endpoints
- `GET  /api/health` — Index status, ticket counts, and active AI provider.
- `GET  /api/examples` — Example incoming tickets from `data/new_tickets_example.json`.
- `GET  /api/tickets?category=auth` — List indexed resolved tickets.
- `POST /api/match` — Rank similar historical tickets for `{ "ticket_id", "subject", "description", "top_n", "use_ai" }`.
- `POST /api/tickets` — Persist and immediately index a newly resolved ticket.
- `POST /api/feedback` — Log agent 👍/👎 feedback for continuous evaluation.

---

## Project Layout

```text
support-ticket-similarity/
├── SOLUTION_DESIGN.md                  # 1-2 page FDE design document
├── README.md                           # Run instructions, API guide, and limitations
├── pom.xml                             # Standard Java 17 Maven configuration
├── run.sh                              # Zero-dependency JDK 17 build/run/test helper
├── data/
│   ├── tickets.json                    # 40 seed resolved historical tickets
│   └── new_tickets_example.json        # 4 incoming test tickets (NT-1..NT-4)
└── src/
    ├── main/
    │   ├── java/com/solari/similarity/
    │   │   ├── Main.java               # CLI + server entrypoint
    │   │   ├── engine/                 # SimilarityEngine (BM25F + 3-gram) & AiAssistClient
    │   │   ├── model/                  # Immutable Java 17 record DTOs & MatchTier enum
    │   │   ├── server/                 # JDK HttpServer REST + static UI handler
    │   │   ├── store/                  # Thread-safe TicketRepository (resolved-only filter)
    │   │   ├── text/                   # TextPreprocessor, PiiRedactor, ResolutionExtractor
    │   │   └── util/                   # RFC-8259 JsonCodec
    │   └── resources/static/index.html # Single-page Support Agent Triage Console
    └── test/java/com/solari/similarity/
        └── SimilarityAssistantTest.java # 10 unit + end-to-end API & cold-start tests
```

---

## Known Limitations & What Is Not Handled Yet in v1

1. **In-Memory Index & JSON File Persistence:** Appropriate for the seed dataset and up to ~50k tickets on a single node, but concurrent multi-instance deployments should swap `TicketRepository` to PostgreSQL (`tsvector` + `pg_trgm` + `pgvector`).
2. **Curated English Synonym & Stemming Rules:** `TextPreprocessor` handles English support morphology and common SaaS paraphrases (`empty`/`nothing`/`blank`, `twice`/`duplicate`), but does not handle non-English tickets or deep domain paraphrases unless the optional embedding mode (`--ai`) is enabled.
3. **Regex-Based PII Scrubbing & Stage Detection:** `PiiRedactor` and symptom-stage caveat rules cover common email, token, card, and non-delivery patterns; production deployment should integrate dedicated DLP redaction (e.g., customer names/account URLs) and Helpdesk RBAC/SSO authentication on `/api/*`.
4. **Static Threshold Constants:** Confidence cutoffs (`STRONG >= 0.52`, `RELATED >= 0.34`) are calibrated against the starter corpus; in production these should be tuned empirically against agent 👍/👎 feedback logs.
# HCL_CASE_STUDY_FINAL
# HCL_CASE_STUDY_FINAL
