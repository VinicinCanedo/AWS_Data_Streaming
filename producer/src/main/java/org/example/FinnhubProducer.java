import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kinesis.KinesisClient;
import software.amazon.awssdk.services.kinesis.model.PutRecordRequest;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

public class FinnhubProducer {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    // --- WebSocket Callback Functions ---
    public static class FinnhubWebSocketListener extends WebSocketListener {
        private final KinesisClient kinesisClient;
        private final String kinesisStreamName;

        public FinnhubWebSocketListener(KinesisClient kinesisClient, String kinesisStreamName) {
            this.kinesisClient = kinesisClient;
            this.kinesisStreamName = kinesisStreamName;
        }

        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            System.out.println("### Connection Opened ###");
            List<String> stockSymbols = Arrays.asList("AAPL", "AMZN", "MSFT", "GOOGL", "TSLA", "NVDA", "META");

            for (String symbol : stockSymbols) {
                try {
                    String subscribeMessage = objectMapper.writeValueAsString(
                            objectMapper.createObjectNode()
                                    .put("type", "subscribe")
                                    .put("symbol", symbol)
                    );
                    webSocket.send(subscribeMessage);
                    System.out.println("Subscribed to: " + symbol);
                } catch (IOException e) {
                    System.err.println("Error sending subscription message: " + e.getMessage());
                }
            }
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            try {
                JsonNode payload = objectMapper.readTree(text);
                System.out.println("Payload received from Finnhub: " + objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload));

                if (payload.has("type") && "trade".equals(payload.get("type").asText()) && payload.has("data")) {
                    ArrayNode trades = (ArrayNode) payload.get("data");
                    for (JsonNode trade : trades) {
                        ObjectNode tradeNode = (ObjectNode) trade;
                        tradeNode.put("ingestion_timestamp", System.currentTimeMillis());

                        if (tradeNode.has("c") && tradeNode.get("c").isArray()) {
                            ArrayNode conditions = (ArrayNode) tradeNode.get("c");
                            StringBuilder conditionsString = new StringBuilder();
                            for (JsonNode condition : conditions) {
                                conditionsString.append(condition.asText()).append(",");
                            }
                            if (conditionsString.length() > 0) {
                                tradeNode.put("conditions", conditionsString.substring(0, conditionsString.length() - 1));
                            }
                        }
                        tradeNode.remove("c");

                        String partitionKey = tradeNode.has("s") ? tradeNode.get("s").asText() : "unknown_symbol";
                        String recordData = objectMapper.writeValueAsString(tradeNode);

                        PutRecordRequest putRecordRequest = PutRecordRequest.builder()
                                .streamName(kinesisStreamName)
                                .data(SdkBytes.fromByteArray(recordData.getBytes(StandardCharsets.UTF_8)))
                                .partitionKey(partitionKey)
                                .build();

                        kinesisClient.putRecord(putRecordRequest);
                        System.out.println("  --> Individual trade sent to Kinesis: " + partitionKey + " | Data: " + recordData);
                    }
                } else if (payload.has("type") && "ping".equals(payload.get("type").asText())) {
                    System.out.println("  --> PING message received from Finnhub (connection alive)");
                } else {
                    System.out.println("  --> Message of another type or with no data: " + payload.get("type"));
                }
            } catch (Exception e) {
                System.err.println("Error processing message or sending to Kinesis: " + e.getMessage());
                System.err.println("Message that caused the error: " + text);
            }
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            System.err.println("### Error: " + t.getMessage() + " ###");
        }

        @Override
        public void onClosed(WebSocket webSocket, int code, String reason) {
            System.out.println("### Connection Closed ### Status: " + code + ", Message: " + reason);
        }
    }

    // --- Main Entry Point ---
    public static void main(String[] args) throws IOException {
        Properties prop = new Properties();
        try (InputStream input = new FileInputStream("application.properties")) {
            prop.load(input);
        } catch (IOException ex) {
            System.err.println("Error loading application.properties. Check if the file exists.");
            throw ex;
        }

        String kinesisStreamName = prop.getProperty("kinesis.stream.name", "stream-finnhub");
        String awsRegion = prop.getProperty("aws.region", "us-east-1");
        String finnhubApiKey = prop.getProperty("finnhub.api.key");

        if (finnhubApiKey == null || finnhubApiKey.isEmpty()) {
            throw new IllegalArgumentException("Finnhub API key was not defined in application.properties.");
        }

        Region region = Region.of(awsRegion);


        // Initialize Kinesis client
        KinesisClient kinesisClient = KinesisClient.builder()
                .region(region)
                .build();

        // Finnhub WebSocket URL with API Key
        String websocketUrl = "wss://ws.finnhub.io?token=" + finnhubApiKey;

        System.out.println("Connecting to Finnhub at: " + websocketUrl);
        System.out.println("Sending data to Kinesis Stream: " + kinesisStreamName + " in region " + awsRegion);

        OkHttpClient client = new OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build();

        Request request = new Request.Builder()
                .url(websocketUrl)
                .build();

        client.newWebSocket(request, new FinnhubWebSocketListener(kinesisClient, kinesisStreamName));

        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
