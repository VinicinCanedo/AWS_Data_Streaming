package com.datalake;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kinesis.KinesisClient;
import software.amazon.awssdk.services.kinesis.model.DescribeStreamRequest;
import software.amazon.awssdk.services.kinesis.model.DescribeStreamResponse;
import software.amazon.awssdk.services.kinesis.model.GetRecordsRequest;
import software.amazon.awssdk.services.kinesis.model.GetRecordsResponse;
import software.amazon.awssdk.services.kinesis.model.GetShardIteratorRequest;
import software.amazon.awssdk.services.kinesis.model.Record;
import software.amazon.awssdk.services.kinesis.model.Shard;
import software.amazon.awssdk.services.kinesis.model.ShardIteratorType;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public class KinesisConsumer {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static void main(String[] args) throws IOException, InterruptedException {
        // --- 1. Carregar as Configurações ---
        Properties prop = new Properties();
        try (InputStream input = new FileInputStream("application.properties")) {
            prop.load(input);
        } catch (IOException ex) {
            System.err.println("Erro ao carregar application.properties. Verifique se o arquivo existe.");
            throw ex;
        }

        String kinesisStreamName = prop.getProperty("kinesis.stream.name", "stream-finnhub");
        String awsRegion = prop.getProperty("aws.region", "us-east-1");
        Region region = Region.of(awsRegion);

        System.out.println("Iniciando consumidor para o stream '" + kinesisStreamName + "' na região '" + awsRegion + "'");

        // --- 2. Inicializar o cliente Kinesis ---
        KinesisClient kinesisClient = KinesisClient.builder()
                .region(region)
                .build();

        // --- 3. Descrever o Stream para obter informações dos shards ---
        try {
            DescribeStreamRequest describeStreamRequest = DescribeStreamRequest.builder()
                    .streamName(kinesisStreamName)
                    .build();

            DescribeStreamResponse describeStreamResponse = kinesisClient.describeStream(describeStreamRequest);
            List<Shard> shards = describeStreamResponse.streamDescription().shards();

            // --- 4. Obter um ShardIterator para cada shard ---
            Map<String, String> shardIterators = new HashMap<>();
            for (Shard shard : shards) {
                String shardId = shard.shardId();
                System.out.println("Obtendo ShardIterator para Shard: " + shardId);
                GetShardIteratorRequest getShardIteratorRequest = GetShardIteratorRequest.builder()
                        .streamName(kinesisStreamName)
                        .shardId(shardId)
                        .shardIteratorType(ShardIteratorType.LATEST) // Ou TRIM_HORIZON
                        .build();

                String shardIterator = kinesisClient.getShardIterator(getShardIteratorRequest).shardIterator();
                shardIterators.put(shardId, shardIterator);
                System.out.println("  Iterator para " + shardId + ": " + shardIterator.substring(0, 30) + "...");
            }

            System.out.println("\n--- Começando a ler registros ---");
            while (!shardIterators.isEmpty()) {
                boolean allShardsEmpty = true;

                for (Map.Entry<String, String> entry : shardIterators.entrySet()) {
                    String shardId = entry.getKey();
                    String shardIterator = entry.getValue();

                    GetRecordsRequest getRecordsRequest = GetRecordsRequest.builder()
                            .shardIterator(shardIterator)
                            .limit(100)
                            .build();

                    GetRecordsResponse getRecordsResponse = kinesisClient.getRecords(getRecordsRequest);
                    List<Record> records = getRecordsResponse.records();
                    String nextShardIterator = getRecordsResponse.nextShardIterator();

                    if (!records.isEmpty()) {
                        allShardsEmpty = false;
                        for (Record record : records) {
                            String data = record.data().asUtf8String();
                            String partitionKey = record.partitionKey();
                            String sequenceNumber = record.sequenceNumber();

                            try {
                                JsonNode parsedData = objectMapper.readTree(data);
                                System.out.println("  Shard: " + shardId + " | PK: " + partitionKey + " | Seq: " + sequenceNumber + " | Data: " + objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(parsedData));
                            } catch (Exception e) {
                                System.out.println("  Shard: " + shardId + " | PK: " + partitionKey + " | Seq: " + sequenceNumber + " | Data (Não JSON): " + data);
                            }
                        }
                    }

                    shardIterators.put(shardId, nextShardIterator);
                    if (nextShardIterator == null) {
                        System.out.println("Shard " + shardId + " atingiu o fim. Removendo-o da lista de leitura.");
                        shardIterators.remove(shardId);
                    }
                }

                // Espera um pouco para evitar chamadas excessivas à API, se não houver registros.
                if (allShardsEmpty) {
                    Thread.sleep(1000);
                }
            }

            System.out.println("Todos os shards foram processados ou fechados. Encerrando consumidor.");

        } catch (Exception e) {
            System.err.println("Ocorreu um erro: " + e.getMessage());
            e.printStackTrace();
        }
    }
}