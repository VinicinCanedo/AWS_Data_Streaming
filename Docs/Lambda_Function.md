### Importações e Configuração Inicial

```python
import json
import base64
import boto3
import os
```

Essas linhas importam as bibliotecas necessárias. `json` para manipular dados JSON, `base64` para decodificar dados, `boto3` para interagir com os serviços da AWS e `os` para acessar variáveis de ambiente.

```python
# Initializes the Boto3 client for DynamoDB
dynamodb = boto3.resource('dynamodb')
```

Aqui, criamos uma instância do cliente **DynamoDB** usando `boto3.resource`. O uso de `resource` em vez de `client` é uma convenção do Boto3 que simplifica a interação com o serviço.

```python
# Get the table name from an environment variable
table_name = os.environ.get('DYNAMODB_TABLE')
```

Esta linha busca o nome da tabela do DynamoDB a partir de uma **variável de ambiente** chamada `DYNAMODB_TABLE`. Isso é uma prática recomendada para evitar o uso de nomes de tabelas "fixos" no código, tornando a função mais flexível e fácil de implantar em diferentes ambientes.

```python
if not table_name:
    # Log the error instead of failing abruptly
    print("Error: 'DYNAMODB_TABLE' environment variable not defined.")
    table = None
else:
    # Assign the 'table' variable to the DynamoDB table object
    table = dynamodb.Table(table_name)
```

Este bloco verifica se a variável de ambiente `DYNAMODB_TABLE` foi definida. Se não foi, ele registra uma mensagem de erro no log e define a variável `table` como `None`, para evitar que a função falhe abruptamente. Caso contrário, ele atribui o objeto da tabela do DynamoDB à variável `table`.

-----

### Função Principal `lambda_handler`

```python
def lambda_handler(event, context):
```

Esta é a função principal de uma função Lambda da AWS. Ela é o ponto de entrada do código. O parâmetro `event` contém os dados que acionaram a função (neste caso, registros do Kinesis), e `context` fornece informações sobre o ambiente de execução da função.

```python
    if table is None:
        return {
            'statusCode': 500,
            'body': json.dumps("Configuration Error: 'DYNAMODB_TABLE' environment variable not defined.")
        }
```

Antes de começar a processar os dados, esta linha verifica se a variável `table` foi inicializada com sucesso. Se for `None`, significa que a variável de ambiente não foi configurada, então a função retorna um erro `500` (Erro de Servidor Interno) para indicar um problema de configuração.

```python
    processed_records = []
```

Uma lista vazia é inicializada para armazenar todos os registros que serão processados antes de serem enviados em lote para o DynamoDB.

```python
    for record in event['Records']:
```

A função Lambda recebe eventos do Kinesis em lotes. Esta linha inicia um loop que itera por cada registro (`record`) dentro do lote.

-----

### Processamento de Cada Registro

```python
        # Decode the Kinesis base64 payload
        try:
            payload = base64.b64decode(record['kinesis']['data'])
            data_string = payload.decode('utf-8')
        except (base64.binascii.Error, UnicodeDecodeError) as e:
            print(f"Error decoding Kinesis data: {e}")
            continue
```

Os dados no Kinesis são codificados em Base64. Este bloco `try-except` tenta decodificar a string Base64 e transformá-la em uma string UTF-8. Se houver um erro na decodificação, ele imprime uma mensagem e passa para o próximo registro, ignorando o registro corrompido.

```python
        # Convert the JSON string to a Python dictionary
        try:
            trade_data = json.loads(data_string)
        except json.JSONDecodeError:
            print(f"Invalid JSON: {data_string}")
            continue
```

Esta parte tenta converter a string de dados em um objeto Python (um dicionário, neste caso) a partir de um formato JSON. Novamente, um bloco `try-except` é usado para lidar com casos em que o JSON não é válido.

```python
        trades_to_process = []
        if trade_data.get('type') == 'trade':
            # Case for the payload format with a list of trades
            trades_to_process = trade_data.get('data', [])
        elif 's' in trade_data and 'p' in trade_data:
            # Case for the individual trade format (without the 'data' array)
            trades_to_process = [trade_data]
        else:
            print(f"Unsupported data format: {trade_data}")
            continue
```

O código verifica o formato do payload recebido. Ele pode vir em dois formatos: um objeto com uma chave `'type'` igual a `'trade'` e os dados de negociação dentro de um array `'data'`, ou um objeto de negociação individual sem o array. Ele lida com ambos os casos, preenchendo a lista `trades_to_process`. Se o formato não for reconhecido, ele ignora o registro.

```python
        if not trades_to_process:
            print(f"No trades to process in this record: {trade_data}")
            continue
```

Uma verificação de segurança para garantir que há realmente negociações para processar. Se a lista estiver vazia, ele ignora o registro.

-----

### Processamento de Cada Negociação

```python
        for trade_info in trades_to_process:
            # Add a try/except block to handle malformed data
            try:
                # ...
            except Exception as e:
                print(f"Error processing a record: {e}")
                continue
```

Este loop itera por cada negociação (`trade_info`) que foi extraída do registro do Kinesis. O bloco `try-except` garante que se algo der errado com uma única negociação (por exemplo, dados ausentes ou de tipo incorreto), o loop não será interrompido e a função continuará processando os outros registros.

```python
                if not isinstance(trade_info, dict):
                    print(f"Unexpected data format, expected a dictionary, but received {type(trade_info)}: {trade_info}")
                    continue
```

Uma verificação adicional para garantir que os dados de negociação são um dicionário, como esperado.

```python
                trade_price = trade_info.get('p', 0.0)
                trade_volume = trade_info.get('v', 0)
                total_value = trade_price * trade_volume
```

Aqui, os dados de preço (`'p'`) e volume (`'v'`) são extraídos do dicionário de negociação, com valores padrão de `0.0` e `0` para evitar erros caso as chaves estejam ausentes. Em seguida, o valor total da negociação é calculado.

```python
                trade_id = f"{record['eventID']}-{trade_info.get('t')}"
```

Uma chave primária única (`trade_id`) é criada, combinando o ID do evento do Kinesis (`eventID`) com o timestamp da negociação (`t`).

```python
                trade_conditions = trade_info.get('c', trade_info.get('conditions'))
                if isinstance(trade_conditions, str):
                    trade_conditions = trade_conditions.split(',')
```

O código tenta obter as condições de negociação, que podem estar na chave `'c'` ou `'conditions'`. Se a informação vier como uma string (em vez de uma lista), ela é dividida em uma lista de strings.

```python
                processed_record = {
                    'trade_id': trade_id,
                    'trade_symbol': trade_info.get('s') or "UNKNOWN",
                    'trade_timestamp': str(trade_info.get('t') or 0),
                    'trade_price': str(trade_price),
                    'trade_volume': str(trade_volume),
                    'total_value': str(total_value),
                    'ingestion_time': str(trade_info.get('ingestion_timestamp') or ""),
                    'trade_conditions': json.dumps(trade_conditions or [])
                }
```

Um novo dicionário é construído, contendo todos os dados processados e transformados. O uso de `str()` garante que todos os valores sejam convertidos em strings, o que é uma boa prática para chaves e valores no DynamoDB. As condições de negociação são serializadas em uma string JSON para garantir compatibilidade.

```python
                processed_record = {k: v for k, v in processed_record.items() if v is not None and v != ""}
```

Esta linha usa uma compreensão de dicionário (dictionary comprehension) para remover todas as chaves que têm valores `None` ou strings vazias, mantendo o item do DynamoDB limpo.

```python
                processed_records.append(processed_record)
```

O registro processado é adicionado à lista `processed_records`.

-----

### Escrita em Lote no DynamoDB

```python
    if processed_records:
        with table.batch_writer() as batch:
            for rec in processed_records:
                try:
                    batch.put_item(
                        Item=rec
                    )
                except Exception as e:
                    print(f"Error inserting item into DynamoDB: {e}")
```

Após o loop de processamento, este bloco verifica se a lista `processed_records` não está vazia. Se houver registros, ele usa o `batch_writer` do Boto3 para enviar os itens para o DynamoDB de forma eficiente em um único lote, em vez de um por um. O bloco `try-except` aqui lida com erros de inserção, permitindo que o programa continue.

-----

### Retorno da Função

```python
    return {
        'statusCode': 200,
        'body': json.dumps(f"Processed {len(processed_records)} records and sent to DynamoDB.")
    }
```

Por fim, a função retorna uma resposta HTTP. O `statusCode` `200` indica sucesso, e o `body` retorna uma mensagem JSON confirmando quantos registros foram processados e enviados.
