### Visão Geral da Estrutura

O código é composto por uma classe principal, `FinnhubProducer`, que contém a lógica para iniciar a conexão WebSocket e gerenciar as configurações. Dentro dela, há uma classe aninhada, `FinnhubWebSocketListener`, que lida com todos os eventos do WebSocket, como a abertura da conexão, o recebimento de mensagens e erros.

-----

### Seção de Importações

As primeiras linhas do código importam todas as classes necessárias para que o programa funcione.

  * `import com.fasterxml.jackson.databind...`: Estas são importações da biblioteca **Jackson**, usadas para converter (fazer o *parse* de) dados JSON. A `ObjectMapper` é a classe principal para essa tarefa.
  * `import okhttp3...`: Estas importações são da biblioteca **OkHttp**, usada para criar e gerenciar a conexão WebSocket.
  * `import software.amazon.awssdk...`: Estas são importações do **AWS SDK para Java**, usadas para interagir com o serviço Amazon Kinesis.
  * `import java.io...`: Classes padrão do Java para manipulação de arquivos de entrada e saída, usadas para carregar o arquivo de configurações (`.properties`).
  * `import java.util...`: Classes utilitárias do Java para estruturas de dados como `List` e `Arrays`.

-----

### Seção do Produtor

Esta é a classe principal que executa o programa.

```java
private static final ObjectMapper objectMapper = new ObjectMapper();
```

Aqui, um único objeto `ObjectMapper` é criado. Ele é usado em todo o programa para converter dados JSON em objetos Java e vice-versa.

-----

### Ouvinte de WebSocket (`FinnhubWebSocketListener`)

Esta classe aninhada (uma classe dentro de outra) é a "mecanismo" que processa as mensagens do WebSocket.

#### Construtor

```java
public FinnhubWebSocketListener(KinesisClient kinesisClient, String kinesisStreamName) {
    this.kinesisClient = kinesisClient;
    this.kinesisStreamName = kinesisStreamName;
}
```

O construtor recebe o **cliente Kinesis** e o **nome do stream**, que serão usados mais tarde para enviar os dados.

#### Método `onOpen`

```java
public void onOpen(WebSocket webSocket, Response response) {
    // ...
    List<String> stockSymbols = Arrays.asList("AAPL", "AMZN", ...);
    for (String symbol : stockSymbols) {
        // ...
        String subscribeMessage = objectMapper.writeValueAsString(...);
        webSocket.send(subscribeMessage);
    }
}
```

Este método é executado **uma única vez**, logo após a conexão WebSocket ser estabelecida com sucesso. Ele cria uma lista de símbolos de ações e, em um laço, envia uma mensagem de "assinatura" (`"subscribe"`) para o servidor Finnhub para cada símbolo, solicitando que ele comece a enviar dados de negociação.

#### Método `onMessage`

```java
public void onMessage(WebSocket webSocket, String text) {
    try {
        JsonNode payload = objectMapper.readTree(text);
        // ...
    } catch (Exception e) {
        // ...
    }
}
```

Este é o coração do programa. Ele é executado toda vez que uma nova mensagem de texto é recebida do WebSocket.

1.  A mensagem de texto (`text`) é convertida em um objeto **JSON** (`payload`).
2.  O código verifica o `type` da mensagem recebida.
3.  Se for do tipo `"trade"`, ele itera sobre cada negociação (`trade`) no array `data`.
4.  Para cada negociação, um **timestamp de ingestão** é adicionado, marcando a hora em que o dado foi processado pelo seu programa.
5.  Em seguida, ele lida com a chave `"c"`, que contém as condições da negociação. O código converte o array de condições em uma única string separada por vírgulas para facilitar o armazenamento.
6.  A chave `"c"` original é removida.
7.  Um `PutRecordRequest` é construído com o nome do stream Kinesis. A chave de partição (`partitionKey`) é definida como o símbolo da ação (por exemplo, "AAPL"), e o corpo da mensagem (`data`) é a string JSON da negociação.
8.  Finalmente, o método `kinesisClient.putRecord` envia a negociação individual para o stream Kinesis.
9.  Se a mensagem for do tipo `"ping"`, ele simplesmente imprime uma mensagem, confirmando que a conexão está ativa.
10. Um bloco `try-catch` lida com quaisquer erros que possam ocorrer durante o processamento da mensagem ou o envio para o Kinesis.

#### Métodos `onFailure` e `onClosed`

Estes métodos são chamados em caso de falha na conexão ou quando a conexão é fechada, respectivamente, e servem para registrar esses eventos.

-----

### Método `main`

Este é o ponto de entrada principal do programa.

1.  **Carrega as configurações:** A primeira parte carrega as propriedades do arquivo `application.properties`, incluindo o nome do stream Kinesis, a região da AWS e a chave da API Finnhub.
2.  **Valida a chave:** Ele verifica se a chave da API Finnhub foi fornecida. Se não, lança um erro.
3.  **Inicia os clientes:** Cria e configura o `KinesisClient` e o `OkHttpClient`.
4.  **Cria a conexão WebSocket:** Ele constrói a URL do WebSocket e, usando o `OkHttpClient`, inicia uma nova conexão com o `FinnhubWebSocketListener` que você acabou de ver.
5.  **Mantém o programa em execução:** `Thread.currentThread().join()` é uma linha importante que impede que o programa principal termine. Ele aguarda indefinidamente, permitindo que o WebSocket continue ouvindo e enviando dados em segundo plano.

Em essência, este código cria um pipeline de dados em tempo real, conectando-se a uma fonte externa de dados e transmitindo-os para um sistema de processamento de dados do lado da AWS.
