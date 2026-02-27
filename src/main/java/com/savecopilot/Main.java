package com.savecopilot;

import java.io.*;
import java.util.Scanner;

/**
 * SAV.e Copilot — CLI prompt che invia richieste all'agente GitHub Copilot.
 * L'agente ha accesso a DocMind e carica automaticamente il documento
 * save-iso-prod-db-schema come contesto per interrogare il database.
 *
 * Termina premendo Ctrl+C.
 */
public class Main {

    private static final String ANSI_RESET  = "\033[0m";
    private static final String ANSI_CYAN   = "\033[36m";
    private static final String ANSI_YELLOW = "\033[33m";
    private static final String ANSI_GREEN  = "\033[32m";
    private static final String ANSI_BOLD   = "\033[1m";

    public static void main(String[] args) {
        Config config = new Config();

        printBanner(config);

        try (CopilotAcpClient client = new CopilotAcpClient(
                config.getCopilotCmd(), config.getCopilotExtraArgs())) {

            // Register shutdown hook for clean Ctrl+C handling
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("\n\n" + ANSI_YELLOW + "Sessione terminata. Arrivederci!" + ANSI_RESET);
            }));

            System.out.print(ANSI_CYAN + "⏳ Inizializzazione connessione con Copilot..." + ANSI_RESET);
            client.initialize();
            System.out.println(" " + ANSI_GREEN + "✓" + ANSI_RESET);

            System.out.print(ANSI_CYAN + "⏳ Creazione sessione (caricamento schema DocMind)..." + ANSI_RESET);
            client.createSession(config.buildSystemPrompt());
            System.out.println(" " + ANSI_GREEN + "✓" + ANSI_RESET);

            System.out.println();
            System.out.println(ANSI_GREEN + "✅ Pronto! Digita la tua domanda e premi Invio." + ANSI_RESET);
            System.out.println(ANSI_YELLOW + "   (Premi Ctrl+C per uscire)" + ANSI_RESET);
            System.out.println();

            Scanner scanner = new Scanner(System.in, "UTF-8");
            while (true) {
                System.out.print(ANSI_BOLD + ANSI_CYAN + "Tu> " + ANSI_RESET);
                if (!scanner.hasNextLine()) break;
                String input = scanner.nextLine().trim();
                if (input.isEmpty()) continue;

                System.out.println();
                System.out.print(ANSI_GREEN + ANSI_BOLD + "Copilot> " + ANSI_RESET);

                try {
                    // Stream response chunk by chunk
                    String response = client.sendMessage(input, chunk -> {
                        System.out.print(chunk);
                        System.out.flush();
                    });

                    // If streaming didn't print anything, print the full response
                    if (response.isEmpty()) {
                        System.out.print(ANSI_YELLOW + "(nessuna risposta ricevuta)" + ANSI_RESET);
                    }
                } catch (Exception e) {
                    System.out.print(ANSI_YELLOW + "[Errore: " + e.getMessage() + "]" + ANSI_RESET);
                }

                System.out.println("\n");
            }

        } catch (Exception e) {
            System.err.println(ANSI_YELLOW + "Errore fatale: " + e.getMessage() + ANSI_RESET);
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void printBanner(Config config) {
        System.out.println();
        System.out.println(ANSI_BOLD + ANSI_CYAN +
            "╔══════════════════════════════════════════╗" + ANSI_RESET);
        System.out.println(ANSI_BOLD + ANSI_CYAN +
            "║        SAV.e Copilot Agent v1.0          ║" + ANSI_RESET);
        System.out.println(ANSI_BOLD + ANSI_CYAN +
            "╚══════════════════════════════════════════╝" + ANSI_RESET);
        System.out.println(ANSI_YELLOW +
            "  Progetto DocMind : " + config.getDocmindProject() + ANSI_RESET);
        System.out.println(ANSI_YELLOW +
            "  Schema di rifetimento: " + config.getDocmindDocument() + ANSI_RESET);
        System.out.println();
    }
}
