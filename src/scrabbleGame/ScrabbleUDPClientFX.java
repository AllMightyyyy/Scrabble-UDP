package scrabbleGame;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.*;
import javafx.stage.Stage;

import java.io.IOException;
import java.net.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class ScrabbleUDPClientFX extends Application {

    private static final int SERVER_PORT = 8888;
    private static final String SERVER_HOST = "localhost";

    private DatagramSocket socket;
    private AtomicBoolean running = new AtomicBoolean(false);

    // UI elements
    private TextField nameField;
    private Button joinButton;
    private TextArea serverMessagesArea;
    private TextField wordField;
    private Button submitWordButton;

    // Scoreboard
    private TableView<PlayerScore> scoreTable;
    private ObservableList<PlayerScore> scoreData;

    // Remember if we've already joined
    private boolean joined = false;

    @Override
    public void start(Stage primaryStage) {
        primaryStage.setTitle("Scrabble Client (JavaFX)");

        // Main layout as a BorderPane, so we can place scoreboard on the right or bottom
        BorderPane root = new BorderPane();

        // Top Section: Join box
        HBox joinBox = new HBox(10);
        Label nameLabel = new Label("Player Name:");
        nameField = new TextField();
        nameField.setPromptText("Enter your name...");
        joinButton = new Button("Join Game");
        joinButton.setOnAction(e -> handleJoin());
        joinBox.getChildren().addAll(nameLabel, nameField, joinButton);
        root.setTop(joinBox);

        // Center: TextArea for messages & Word submission
        VBox centerBox = new VBox(10);

        // Server messages
        serverMessagesArea = new TextArea();
        serverMessagesArea.setEditable(false);
        serverMessagesArea.setPrefHeight(200);
        serverMessagesArea.setWrapText(true);

        // Word submission
        HBox wordBox = new HBox(10);
        Label wordLabel = new Label("Your Word:");
        wordField = new TextField();
        wordField.setPromptText("Enter your word...");
        submitWordButton = new Button("Submit Word");
        submitWordButton.setOnAction(e -> handleSubmitWord());
        submitWordButton.setDisable(true); // disabled until we receive letters
        wordBox.getChildren().addAll(wordLabel, wordField, submitWordButton);

        centerBox.getChildren().addAll(serverMessagesArea, wordBox);
        root.setCenter(centerBox);

        // Right Side: Scoreboard
        scoreData = FXCollections.observableArrayList();
        scoreTable = createScoreTable();
        // Place scoreboard in a VBox or directly set as right node
        VBox scoreBox = new VBox(new Label("Scoreboard"), scoreTable);
        scoreBox.setSpacing(5);
        root.setRight(scoreBox);

        // Scene + load CSS
        Scene scene = new Scene(root, 800, 400);  // a bit wider to accommodate the table
        scene.getStylesheets().add(getClass().getResource("style.css").toExternalForm());
        primaryStage.setScene(scene);
        primaryStage.show();

        // Initialize UDP socket & start listener
        try {
            socket = new DatagramSocket();
            socket.setReuseAddress(true);
            running.set(true);

            Thread listenerThread = new Thread(this::listenForServer);
            listenerThread.setDaemon(true);
            listenerThread.start();
        } catch (SocketException ex) {
            showMessage("ERROR: Unable to open UDP socket: " + ex.getMessage());
            ex.printStackTrace();
        }
    }

    /**
     * Create the TableView for scoreboard
     */
    private TableView<PlayerScore> createScoreTable() {
        TableView<PlayerScore> table = new TableView<>(scoreData);

        TableColumn<PlayerScore, String> playerCol = new TableColumn<>("Player");
        playerCol.setCellValueFactory(new PropertyValueFactory<>("player"));
        playerCol.setPrefWidth(150);

        TableColumn<PlayerScore, Integer> scoreCol = new TableColumn<>("Score");
        scoreCol.setCellValueFactory(new PropertyValueFactory<>("score"));
        scoreCol.setPrefWidth(60);

        table.getColumns().addAll(playerCol, scoreCol);
        table.setPrefHeight(300);

        return table;
    }

    /**
     * Sends the JOIN message to the server.
     */
    private void handleJoin() {
        if (joined) {
            showMessage("Already joined the game!");
            return;
        }
        String name = nameField.getText().trim();
        if (name.isEmpty()) {
            showMessage("Please enter a valid name before joining.");
            return;
        }
        sendMessage("JOIN:" + name);
        joined = true;
        showMessage("JOIN request sent. Waiting for server response...");
    }

    /**
     * Sends the WORD submission to the server.
     */
    private void handleSubmitWord() {
        if (!joined) {
            showMessage("You must join first!");
            return;
        }
        String word = wordField.getText().trim();
        if (word.isEmpty()) {
            showMessage("Cannot submit an empty word!");
            return;
        }
        sendMessage("WORD:" + word);
        showMessage("Submitted word: " + word);

        // Clear the field and disable until next round letters arrive
        wordField.clear();
        submitWordButton.setDisable(true);
    }

    /**
     * Continuously listens for messages from the server in a background thread.
     */
    private void listenForServer() {
        byte[] buf = new byte[2048];
        while (running.get()) {
            try {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                socket.receive(packet);

                String serverMsg = new String(packet.getData(), 0, packet.getLength()).trim();
                Platform.runLater(() -> handleServerMessage(serverMsg));

            } catch (SocketException e) {
                // Socket closed
                running.set(false);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Parses and handles server messages on the JavaFX thread.
     */
    private void handleServerMessage(String msg) {
        showMessage("Server: " + msg);

        // Because we sometimes get multi-line messages (like scores),
        // let's split by newline and handle line by line.
        String[] lines = msg.split("\n");

        for (String line : lines) {
            if (line.startsWith("JOIN_OK:")) {
                // Possibly update UI state
            }
            else if (line.startsWith("JOIN_REJECT:")) {
                joined = false;
                showMessage("Join rejected by server.");
                showPopup("Join Rejected", "The server has rejected your join request.", Alert.AlertType.ERROR);
            }
            else if (line.startsWith("ROUND_START:")) {
                // Round start info (e.g., "ROUND_START:1")
                String roundNum = line.substring("ROUND_START:".length()).trim();
                showPopup("Round Started", "Round " + roundNum + " has begun!", Alert.AlertType.INFORMATION);
            }
            else if (line.startsWith("ROUND_LETTERS:")) {
                // Let user input a word now
                submitWordButton.setDisable(false);
                String letters = line.substring("ROUND_LETTERS:".length()).trim();
                showPopup("Round Letters", "Letters for this round: " + letters, Alert.AlertType.INFORMATION);
            }
            else if (line.startsWith("RECEIVED:")) {
                // Acknowledged
            }
            else if (line.startsWith("ROUND_OVER:")) {
                // Round over
                String roundNum = line.substring("ROUND_OVER:".length()).trim();
                showPopup("Round Over", "Round " + roundNum + " is over. Scores have been updated.", Alert.AlertType.INFORMATION);
            }
            else if (line.startsWith("Current Scores:")) {
                // Next lines (if any) contain scoreboard info
                // We'll handle it after we exit this for loop, because we want to parse
                // them in the overall message. Or we can just handle inline:
                // Do nothing here, we parse in the iteration below.
            }
            else if (line.startsWith("WINNER:")) {
                // "WINNER:playerKey:score"
                showPopup("Game Over", line, Alert.AlertType.INFORMATION);
                closeClient();
            }
            else if (line.startsWith("TIE:")) {
                // "TIE:[player1, player2]:score"
                showPopup("Game Over (Tie)", line, Alert.AlertType.INFORMATION);
                closeClient();
            }
            else {
                // Could be part of the "Current Scores" lines
                if (line.contains("->") && line.contains("points")) {
                    // This is likely a line with "ip:port -> X points"
                    parseScoreLine(line);
                }
                else {
                    // A generic broadcast or other message
                }
            }
        }
    }

    /**
     * Parse a single scoreboard line of the form:
     * "PlayerName -> 10 points"
     */
    private void parseScoreLine(String line) {
        if (line.trim().isEmpty()) return;

        // Example line: "PlayerName -> 10 points"
        try {
            String[] parts = line.split("->");
            if (parts.length < 2) return;
            String playerPart = parts[0].trim();  // e.g., "PlayerName"
            String scorePart = parts[1].trim();   // e.g., "10 points"

            // Remove " points"
            scorePart = scorePart.replace(" points", "").trim();

            int score = Integer.parseInt(scorePart);

            // Update the scoreboard entry for this player
            updateScoreboard(playerPart, score);

        } catch (Exception e) {
            // ignore parse errors
        }
    }

    /**
     * Update the scoreboard with the given player -> score mapping.
     */
    private void updateScoreboard(String player, int score) {
        // If player already in scoreboard, update. Else add new row.
        for (PlayerScore ps : scoreData) {
            if (ps.getPlayer().equals(player)) {
                ps.setScore(score);
                scoreTable.refresh();
                return;
            }
        }
        // Not found, add new
        scoreData.add(new PlayerScore(player, score));
        scoreTable.refresh();
    }

    /**
     * Show a popup (alert) with the given title/message and type.
     */
    private void showPopup(String title, String message, Alert.AlertType type) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(null); // no header
        alert.setContentText(message);
        alert.showAndWait();
    }

    /**
     * Gracefully close the client (stop listening, close socket).
     */
    private void closeClient() {
        running.set(false);
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }

    /**
     * Utility method to send a message to the server.
     */
    private void sendMessage(String message) {
        try {
            InetAddress address = InetAddress.getByName(SERVER_HOST);
            byte[] data = message.getBytes();
            DatagramPacket packet = new DatagramPacket(data, data.length, address, SERVER_PORT);
            socket.send(packet);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Safely append a message to the TextArea.
     */
    private void showMessage(String text) {
        serverMessagesArea.appendText(text + "\n");
    }

    @Override
    public void stop() throws Exception {
        // Called when the application is closed (e.g., window is closed).
        super.stop();
        closeClient();
    }

    public static void main(String[] args) {
        launch(args);
    }

    /**
     * Simple JavaFX model class for scoreboard entries.
     */
    public static class PlayerScore {
        private final String player;
        private int score;

        public PlayerScore(String player, int score) {
            this.player = player;
            this.score = score;
        }

        public String getPlayer() {
            return player;
        }

        public int getScore() {
            return score;
        }

        public void setScore(int newScore) {
            this.score = newScore;
        }
    }
}
