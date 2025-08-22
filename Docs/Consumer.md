### Seção de Importações

```java
package com.datalake;
```

Esta linha declara o pacote ao qual a classe pertence. É uma forma de organizar o código Java.

```java
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
```

Importa classes da biblioteca **Jackson**, usadas para lidar com dados JSON. A `ObjectMapper` é a ferramenta principal para converter strings JSON em objetos Java (`JsonNode`) para que possam ser processados.

```java
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kinesis...;
```

Estas linhas importam o **AWS SDK para Java**. Elas fornecem as classes necessárias para interagir com o serviço Kinesis, como `KinesisClient` (o cliente que se conecta ao Kinesis) e as classes `Request` e `Response` para descrever e obter registros e shards do stream.

```java
import java.io...;
import java.util...;
```

Importa classes do Java padrão para manipulação de arquivos (`FileInputStream`, `IOException`) e para estruturas de dados (`HashMap`, `List`, `Map`, `Properties`).

-----

### Classe Principal e Método `main`

```java
public class KinesisConsumer {
```

Esta é a declaração da classe principal, `KinesisConsumer`.

```java
    private static final ObjectMapper objectMapper = new ObjectMapper();
```

Cria uma única instância do `ObjectMapper` que será usada para toda a classe, o que é mais eficiente do que criar uma nova instância a cada vez.

```java
    public static void main(String[] args) throws IOException, InterruptedException {
```

Este é o ponto de entrada do programa. O `main` é o primeiro método a ser executado. Ele declara que pode lançar exceções de E/S (`IOException`) e de interrupção de thread (`InterruptedException`).

-----

### 1\. Carregar Configurações

```java
        Properties prop = new Properties();
        try (InputStream input = new FileInputStream("application.properties")) {
            prop.load(input);
        } catch (IOException ex) {
            System.err.println("Erro ao carregar application.properties. Verifique se o arquivo existe.");
            throw ex;
        }
```

Este bloco carrega as configurações do arquivo `application.properties`. Ele usa um bloco `try-with-resources` para garantir que o stream de entrada seja fechado automaticamente. Se o arquivo não puder ser carregado, uma mensagem de erro é impressa e a exceção é relançada.

```java
        String kinesisStreamName = prop.getProperty("kinesis.stream.name", "stream-finnhub");
        String awsRegion = prop.getProperty("aws.region", "us-east-1");
        Region region = Region.of(awsRegion);
```

As linhas extraem o nome do stream e a região da AWS do arquivo de propriedades, usando valores padrão caso as propriedades não existam. A string da região é convertida para um objeto `Region`.

-----

### 2\. Inicializar o Cliente Kinesis

```java
        KinesisClient kinesisClient = KinesisClient.builder()
                .region(region)
                .build();
```

Cria e configura o cliente do Kinesis com a região especificada. Este objeto é usado para todas as interações com o serviço Kinesis.

-----

### 3\. Descrever o Stream

```java
        try {
            DescribeStreamRequest describeStreamRequest = DescribeStreamRequest.builder()
                    .streamName(kinesisStreamName)
                    .build();
```

Um objeto de requisição (`DescribeStreamRequest`) é criado para obter informações sobre o stream Kinesis, como os seus shards.

```java
            DescribeStreamResponse describeStreamResponse = kinesisClient.describeStream(describeStreamRequest);
            List<Shard> shards = describeStreamResponse.streamDescription().shards();
```

A requisição é enviada, e a resposta contém a descrição do stream, da qual uma lista de shards é extraída. Um shard é um grupo de registros dentro do stream.

-----

### 4\. Obter Iterators e Ler Registros

```java
            Map<String, String> shardIterators = new HashMap<>();
            for (Shard shard : shards) {
                String shardId = shard.shardId();
                // ...
                GetShardIteratorRequest getShardIteratorRequest = GetShardIteratorRequest.builder()
                        .streamName(kinesisStreamName)
                        .shardId(shardId)
                        .shardIteratorType(ShardIteratorType.LATEST)
                        .build();

                String shardIterator = kinesisClient.getShardIterator(getShardIteratorRequest).shardIterator();
                shardIterators.put(shardId, shardIterator);
            }
```

Um **iterator de shard** é um ponteiro para um local específico dentro de um shard. Este bloco itera sobre cada shard e obtém um iterator para ele. `ShardIteratorType.LATEST` significa que ele começará a ler a partir do registro mais recente que chegou ao stream. O iterator de cada shard é armazenado em um mapa.

```java
            while (!shardIterators.isEmpty()) {
                boolean allShardsEmpty = true;
                for (Map.Entry<String, String> entry : shardIterators.entrySet()) {
                    // ...
                    GetRecordsRequest getRecordsRequest = GetRecordsRequest.builder()
                            .shardIterator(shardIterator)
                            .limit(100)
                            .build();

                    GetRecordsResponse getRecordsResponse = kinesisClient.getRecords(getRecordsRequest);
                    List<Record> records = getRecordsResponse.records();
                    String nextShardIterator = getRecordsResponse.nextShardIterator();
```

Este loop `while` principal continua enquanto houver iterators a serem processados. Ele itera sobre cada iterator no mapa, cria uma requisição para obter os registros a partir daquele ponto (`GetRecordsRequest`) e envia a requisição. A resposta contém uma lista de registros e o `nextShardIterator`, que aponta para o próximo local a ser lido.

```java
                    if (!records.isEmpty()) {
                        allShardsEmpty = false;
                        for (Record record : records) {
                            String data = record.data().asUtf8String();
                            // ...
                            try {
                                JsonNode parsedData = objectMapper.readTree(data);
                                System.out.println("  Shard: " + shardId + " | PK: " + partitionKey + " | Seq: " + sequenceNumber + " | Data: " + objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(parsedData));
                            } catch (Exception e) {
                                System.out.println("  Shard: " + shardId + " | PK: " + partitionKey + " | Seq: " + sequenceNumber + " | Data (Não JSON): " + data);
                            }
                        }
                    }
```

Se a lista de registros não estiver vazia, o código itera sobre cada registro. Ele extrai os dados, a chave de partição e o número de sequência. Em seguida, tenta converter os dados em JSON e imprime o resultado formatado. Se os dados não forem JSON válidos, ele imprime a string bruta.

```java
                    shardIterators.put(shardId, nextShardIterator);
                    if (nextShardIterator == null) {
                        System.out.println("Shard " + shardId + " atingiu o fim. Removendo-o da lista de leitura.");
                        shardIterators.remove(shardId);
                    }
```

Após processar os registros de um shard, o iterator no mapa é atualizado para o `nextShardIterator` retornado. Se o `nextShardIterator` for `null`, significa que o shard foi fechado (não há mais registros a serem lidos) e ele é removido do mapa, fazendo com que o loop `while` termine quando todos os shards forem processados.

```java
                if (allShardsEmpty) {
                    Thread.sleep(1000);
                }
            }
```

Se o loop interno terminar e nenhum registro tiver sido encontrado em nenhum dos shards (`allShardsEmpty` é `true`), o programa faz uma pausa de 1 segundo (`Thread.sleep(1000)`) para evitar chamadas excessivas e caras à API do Kinesis, seguindo a lógica de "polling".

```java
        } catch (Exception e) {
            System.err.println("Ocorreu um erro: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
```

O bloco `catch` final captura quaisquer exceções que ocorram durante a execução, imprime uma mensagem de erro e a rastreabilidade da pilha (`e.printStackTrace()`) para ajudar na depuração.

-----

Este código é um exemplo clássico de um consumidor Kinesis com *polling*, onde ele periodicamente pergunta ao stream por novos dados. Ele é fundamental para a segunda metade de um pipeline de dados em tempo real.