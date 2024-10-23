package com.project;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.event.ActionEvent;
import javafx.application.Platform;
import java.net.URL;
import java.util.ResourceBundle;
import javafx.fxml.Initializable;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpRequest.BodyPublishers;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import javafx.util.Duration;
import org.json.JSONObject;

public class ChatController implements Initializable {
    @FXML
    public TextArea userInput;
    @FXML
    private VBox chat;
    @FXML
    private ScrollPane scrollPane;
    @FXML
    private Button uploadButton, sendButton, cancelButton;


    private final HttpClient httpClient = HttpClient.newHttpClient();
    private CompletableFuture<HttpResponse<InputStream>> streamRequest;
    private CompletableFuture<HttpResponse<String>> completeRequest;
    private AtomicBoolean isCancelled = new AtomicBoolean(false);
    private InputStream currentInputStream;
    private ExecutorService executorService = Executors.newSingleThreadExecutor();
    private Future<?> streamReadingTask;
    private boolean isFirst = false;

    public void initialize(URL url, ResourceBundle rb) {
        setButtonsIdle();
    }

    @FXML
    private void callStream(ActionEvent event) {
        setButtonsRunning();
        isCancelled.set(false);
        String userMessage = userInput.getText().trim();


        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:11434/api/generate"))
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString("{\"model\": \"llama3.2:1b\", \"prompt\": \"" + userMessage + "\"}"))
                .build();

        addMessageToHistory(new StringBuilder(userMessage), true);
        isFirst = true;
        streamRequest = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                .thenApply(response -> {
                    currentInputStream = response.body();
                    streamReadingTask = executorService.submit(() -> {
                        try (BufferedReader reader = new BufferedReader(new InputStreamReader(currentInputStream))) {
                            StringBuilder fullResponse = new StringBuilder();
                            String line;

                            while ((line = reader.readLine()) != null) {
                                if (isCancelled.get()) {
                                    System.out.println("Stream cancelled");
                                    break;
                                }
                                JSONObject jsonResponse = new JSONObject(line);
                                String responseText = jsonResponse.getString("response");
                                fullResponse.append(responseText); // Acumular texto y agregar un espacio
                            }

                            // Una vez que terminamos de leer, añadimos el mensaje a la historia
                            final String finalResponse = fullResponse.toString(); // Eliminar espacio extra
                            Platform.runLater(() -> addMessageToHistory(new StringBuilder(finalResponse), false)); // false = mensaje del modelo

                        } catch (Exception e) {
                            e.printStackTrace();
                            Platform.runLater(() -> {
                                addMessageToHistory(new StringBuilder("Error during streaming."), false);
                                setButtonsIdle();
                            });
                        } finally {
                            try {
                                if (currentInputStream != null) {
                                    System.out.println("Cancelling InputStream in finally");
                                    currentInputStream.close();
                                }
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                            Platform.runLater(this::setButtonsIdle);
                        }
                    });
                    return response;
                })
                .exceptionally(e -> {
                    if (!isCancelled.get()) {
                        e.printStackTrace();
                    }
                    Platform.runLater(this::setButtonsIdle);
                    return null;
                });
    }

    private void addMessageToHistory(StringBuilder messageBuilder, boolean isUser) {
        System.out.println("addMessageToHistory called with message: " + messageBuilder.toString());
        Platform.runLater(() -> {
            HBox messageContainer = new HBox(10); // Espaciado de 10 píxeles entre los elementos

            // Crear un Label para el mensaje
            Label messageLabel = new Label();
            messageLabel.setWrapText(true);
            messageLabel.setStyle("-fx-font-size: 26px; -fx-font-weight: bold; -fx-text-fill: black;");

            // Establecer el texto inicial basado en si es un usuario o un modelo
            if (isUser) {
                messageLabel.setText("Tu: " + messageBuilder.toString()); // Mostrar inmediatamente
            } else {
                messageLabel.setText("Ollama: "); // Dejar vacío para mostrar lentamente más tarde
            }

            // Añadir el Label al HBox
            messageContainer.getChildren().add(messageLabel);

            // Añadir el HBox al VBox
            chat.getChildren().add(messageContainer);

            // Actualiza el scroll para mostrar el último mensaje
            scrollPane.layout();
            scrollPane.setVvalue(1.0);

            // Iniciar la animación para mostrar el mensaje lentamente solo si es del modelo
            if (!isUser) {
                showTextGradually(messageLabel, messageBuilder.toString());
            }
        });
    }


    private void showTextGradually(Label label, String fullText) {
        label.setText("Ollama: ");
        StringBuilder displayedText = new StringBuilder();
        Timeline timeline = new Timeline(new KeyFrame(Duration.seconds(0.01), event -> {
            if (displayedText.length() < fullText.length()) {
                displayedText.append(fullText.charAt(displayedText.length()));
                label.setText(displayedText.toString());
            }
        }));

        timeline.setCycleCount(fullText.length()); // Repetir para cada carácter del texto completo
        timeline.play(); // Iniciar la animación
    }

    @FXML
    private void callBreak(ActionEvent event) {
        isCancelled.set(true);
        cancelStreamRequest();
        cancelCompleteRequest();
        Platform.runLater(this::setButtonsIdle);
    }

    @FXML
    private void uploadFile(ActionEvent event) {

    }

    private void cancelStreamRequest() {
        if (streamRequest != null && !streamRequest.isDone()) {
            try {
                if (currentInputStream != null) {
                    System.out.println("Cancelling InputStream");
                    currentInputStream.close();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            System.out.println("Cancelling StreamRequest");
            if (streamReadingTask != null) {
                streamReadingTask.cancel(true);
            }
            streamRequest.cancel(true);
        }
    }

    private void cancelCompleteRequest() {
        if (completeRequest != null && !completeRequest.isDone()) {
            System.out.println("Cancelling CompleteRequest");
            completeRequest.cancel(true);
        }
    }

    private void setButtonsRunning() {
        sendButton.setDisable(true);
        uploadButton.setDisable(true);
        cancelButton.setDisable(false);
    }

    private void setButtonsIdle() {
        sendButton.setDisable(false);
        uploadButton.setDisable(false);
        cancelButton.setDisable(true);
        streamRequest = null;
        completeRequest = null;
    }
   
}
