package scrabbleGame;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.*;
import java.util.*;

import net.sf.extjwnl.JWNLException;

public class ScrabbleUDPServer {

    private static final int SERVER_PORT = 8888;
    private static final int MAX_PLAYERS = 4;
    private static final int NUM_ROUNDS = 5;

    // Player info: key= "ip:port", value=score
    private final Map<String, Integer> players = Collections.synchronizedMap(new HashMap<>());

    private final Map<String, String> playerNames = Collections.synchronizedMap(new HashMap<>());

    // Track player's last-submitted word for each round
    // roundSubmissionMap<roundNumber, Map<playerKey, submittedWord>>
    private final Map<Integer, Map<String, String>> roundSubmissionMap =
            Collections.synchronizedMap(new HashMap<>());

    // Game states
    private volatile boolean gameStarted = false;
    private volatile int currentRound = 0;

    // For dictionary validation
    private WordValidator wordValidator;

    // We hold a reference to the server socket so we can broadcast
    private DatagramSocket serverSocket;

    public static void main(String[] args) {
        ScrabbleUDPServer server = new ScrabbleUDPServer();
        server.start();
    }

    public void start() {
        try {
            // Initialize dictionary
            wordValidator = new WordValidator();

            // Create and bind the server socket
            serverSocket = new DatagramSocket(SERVER_PORT);

            System.out.println("UDP Server started on port " + SERVER_PORT);

            // Start a thread to read console input (for manual "start" command)
            Thread consoleThread = new Thread(this::handleConsoleInput);
            consoleThread.start();

            // Start a thread to listen for UDP packets
            Thread udpListenerThread = new Thread(this::udpListenerLoop);
            udpListenerThread.start();

            // Wait until the game starts (either "start" typed or 4 players joined)
            synchronized (this) {
                while (!gameStarted) {
                    wait(); // Wait until we're notified that gameStarted = true
                }
            }

            // Now run the 5 rounds
            for (int round = 1; round <= NUM_ROUNDS; round++) {
                currentRound = round;
                // Create a new submissions map for this round
                roundSubmissionMap.put(round, Collections.synchronizedMap(new HashMap<>()));

                // Generate letters
                String letters = generateRandomLetters(10);
                System.out.println("Round " + round + " letters: " + letters);

                // Broadcast to all players
                broadcastMessage("ROUND_START:" + round);
                broadcastMessage("ROUND_LETTERS:" + letters);

                // Wait for all players to submit (no timeouts).
                // We'll block here until each of the active players has a submission recorded.
                waitForAllSubmissions(round);

                // Now we have all submissions. Validate & score.
                scoreRound(round, letters);

                // Broadcast round results
                broadcastScores("ROUND_OVER:" + round);
            }

            // All rounds complete, announce winner
            broadcastFinalWinner();
            System.out.println("Game finished. Shutting down.");

            // Close socket
            serverSocket.close();
            // End threads (they’ll end once socket is closed / no data, or we can system-exit)
            System.exit(0);

        } catch (IOException | JWNLException | InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * Handle console input so the server admin can type "start" to begin the game.
     */
    private void handleConsoleInput() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            while (!gameStarted) {
                String line = reader.readLine();
                if (line != null && line.trim().equalsIgnoreCase("start")) {
                    synchronized (this) {
                        gameStarted = true;
                        notifyAll();
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Continuously listens for UDP packets from clients.
     */
    private void udpListenerLoop() {
        byte[] buf = new byte[1024];

        while (true) {
            try {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                serverSocket.receive(packet);

                String msg = new String(packet.getData(), 0, packet.getLength()).trim();
                String clientKey = packet.getAddress().getHostAddress() + ":" + packet.getPort();

                // Handle the message in another method
                handleClientMessage(msg, clientKey, packet);
            } catch (SocketException e) {
                // This happens when socket is closed. We can just break the loop.
                System.out.println("Server socket closed, UDP listener thread terminating.");
                break;
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Processes messages from clients.
     */
    private void handleClientMessage(String msg, String clientKey, DatagramPacket packet) {
        // If game not started, handle JOIN
        if (!gameStarted) {
            if (msg.startsWith("JOIN:")) {
                handleJoin(msg, clientKey, packet);
            }
        } else {
            // The game has started, handle "WORD:xxxx" if it's relevant to current round
            if (msg.startsWith("WORD:")) {
                handleWordSubmission(msg, clientKey, packet);
            }
        }
    }

    /**
     * Handle "JOIN:name" messages if game hasn't started yet and there's room in the lobby.
     */
    private void handleJoin(String msg, String clientKey, DatagramPacket packet) {
        // parse out the player's name
        String playerName = msg.substring("JOIN:".length()).trim();

        synchronized (this) {
            if (players.size() < MAX_PLAYERS && !players.containsKey(clientKey)) {
                players.put(clientKey, 0);
                playerNames.put(clientKey, playerName); // Store the player's name
                System.out.println("Player joined -> " + playerName + " at " + clientKey);
                sendMessage(packet.getAddress(), packet.getPort(),
                        "JOIN_OK:Welcome " + playerName);
            } else {
                // Lobby is full or player already joined
                sendMessage(packet.getAddress(), packet.getPort(), "JOIN_REJECT:Lobby Full or Duplicate");
            }

            // If we just hit 4 players, auto-start the game
            if (!gameStarted && players.size() == MAX_PLAYERS) {
                gameStarted = true;
                notifyAll();  // wake up main thread
            }
        }
    }

    /**
     * Handle "WORD:xxxx" from a client during the current round.
     */
    private void handleWordSubmission(String msg, String clientKey, DatagramPacket packet) {
        int round = currentRound;  // volatile read

        // If the current round is 0 (not started) or beyond NUM_ROUNDS, ignore
        if (round < 1 || round > NUM_ROUNDS) {
            return;
        }

        // Extract the word
        String submittedWord = msg.substring("WORD:".length()).trim();

        // Record the submission
        Map<String, String> submissions = roundSubmissionMap.get(round);
        if (submissions != null && !submissions.containsKey(clientKey)) {
            submissions.put(clientKey, submittedWord);
            // Acknowledge receipt
            sendMessage(packet.getAddress(), packet.getPort(), "RECEIVED:Your word for round " + round);

            // Check if that was the last submission needed
            // (i.e., if we have submissions from all players)
            if (submissions.size() == players.size()) {
                synchronized (this) {
                    // Notify the main thread that all submissions are in
                    notifyAll();
                }
            }
        }
    }

    /**
     * Wait (block) until we've collected a submission from all players in the specified round.
     */
    private void waitForAllSubmissions(int round) throws InterruptedException {
        while (true) {
            synchronized (this) {
                Map<String, String> submissions = roundSubmissionMap.get(round);
                if (submissions != null && submissions.size() == players.size()) {
                    break; // all players have submitted
                }
                wait(); // wait to be notified when a new submission arrives
            }
        }
    }

    /**
     * Validate and score each player's submission for the given round.
     */
    private void scoreRound(int round, String letters) {
        Map<String, String> submissions = roundSubmissionMap.get(round);

        for (Map.Entry<String, String> entry : submissions.entrySet()) {
            String playerKey = entry.getKey();
            String submittedWord = entry.getValue();

            boolean valid = validateWord(submittedWord, letters);
            int scoreGain = valid ? submittedWord.length() : 0;

            // Update player's total score
            int oldScore = players.get(playerKey);
            players.put(playerKey, oldScore + scoreGain);
        }
    }

    /**
     * Checks if the submitted word is valid with respect to the given letters
     * and WordNet dictionary.
     */
    private boolean validateWord(String word, String letters) {
        // Check that all letters can be formed from the pool
        if (!canFormWord(word.toUpperCase(), letters.toUpperCase())) {
            return false;
        }
        // Check dictionary via WordNet
        return wordValidator.isValidWord(word);
    }

    /**
     * Verifies that 'word' can be formed from 'letters' (counting multiplicities).
     */
    private boolean canFormWord(String word, String letters) {
        Map<Character, Integer> freq = new HashMap<>();
        for (char c : letters.toCharArray()) {
            freq.put(c, freq.getOrDefault(c, 0) + 1);
        }
        for (char c : word.toCharArray()) {
            if (!freq.containsKey(c) || freq.get(c) == 0) {
                return false;
            }
            freq.put(c, freq.get(c) - 1);
        }
        return true;
    }

    /**
     * Generates N random uppercase letters.
     */
    private String generateRandomLetters(int n) {
        Random random = new Random();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            char c = (char) ('A' + random.nextInt(26));
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * Broadcast the current scores with a given prefix.
     */
    private void broadcastScores(String prefix) {
        // Build scoreboard message
        StringBuilder sb = new StringBuilder();
        sb.append(prefix).append("\n");
        sb.append("Current Scores:\n");
        synchronized (players) {
            for (Map.Entry<String, Integer> entry : players.entrySet()) {
                String clientKey = entry.getKey();
                int score = entry.getValue();
                String playerName = playerNames.get(clientKey); // Get player name
                sb.append(playerName).append(" -> ").append(score).append(" points\n");
            }
        }
        broadcastMessage(sb.toString());
    }

    /**
     * Announce the final winner (or tie).
     */
    private void broadcastFinalWinner() {
        int maxScore = -1;
        List<String> winners = new ArrayList<>();
        synchronized (players) {
            for (Map.Entry<String, Integer> entry : players.entrySet()) {
                int score = entry.getValue();
                String playerName = playerNames.get(entry.getKey()); // Get player name
                if (score > maxScore) {
                    maxScore = score;
                    winners.clear();
                    winners.add(playerName); // Use player name
                } else if (score == maxScore) {
                    winners.add(playerName); // Use player name
                }
            }
        }
        String result;
        if (winners.size() == 1) {
            result = "WINNER:" + winners.get(0) + ":" + maxScore;
        } else {
            result = "TIE:" + winners + ":" + maxScore;
        }
        broadcastMessage(result);
    }

    /**
     * Broadcast a message to all players.
     */
    private void broadcastMessage(String message) {
        synchronized (players) {
            for (String clientKey : players.keySet()) {
                String[] parts = clientKey.split(":");
                try {
                    InetAddress addr = InetAddress.getByName(parts[0]);
                    int port = Integer.parseInt(parts[1]);
                    sendMessage(addr, port, message);
                } catch (UnknownHostException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * Send a single UDP message to a client.
     */
    private void sendMessage(InetAddress address, int port, String message) {
        byte[] data = message.getBytes();
        DatagramPacket packet = new DatagramPacket(data, data.length, address, port);
        try {
            serverSocket.send(packet);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
