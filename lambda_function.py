import json
import base64
import boto3
import os

# Inicializa o cliente Boto3 para o DynamoDB
dynamodb = boto3.resource('dynamodb')
# Obtenha o nome da tabela a partir de uma variável de ambiente
table_name = os.environ.get('DYNAMODB_TABLE')
if not table_name:
    # Registra o erro em vez de falhar abruptamente
    print("Erro: Variável de ambiente 'DYNAMODB_TABLE' não definida.")
    table = None
else:
    # Atribui a variável 'table' para o objeto da tabela DynamoDB
    table = dynamodb.Table(table_name)

def lambda_handler(event, context):
    if table is None:
        return {
            'statusCode': 500,
            'body': json.dumps("Erro de configuração: Variável de ambiente 'DYNAMODB_TABLE' não definida.")
        }
        
    processed_records = []

    for record in event['Records']:
        # Decodifica o payload base64 do Kinesis
        try:
            payload = base64.b64decode(record['kinesis']['data'])
            data_string = payload.decode('utf-8')
        except (base64.binascii.Error, UnicodeDecodeError) as e:
            print(f"Erro ao decodificar dados do Kinesis: {e}")
            continue
        
        # Converte a string JSON para um dicionário Python
        try:
            trade_data = json.loads(data_string)
        except json.JSONDecodeError:
            print(f"JSON inválido: {data_string}")
            continue

        trades_to_process = []
        if trade_data.get('type') == 'trade':
            # Caso seja o formato de payload com lista de trades
            trades_to_process = trade_data.get('data', [])
        elif 's' in trade_data and 'p' in trade_data:
            # Caso seja o formato de trade individual (sem o array 'data')
            trades_to_process = [trade_data]
        else:
            print(f"Formato de dado não suportado: {trade_data}")
            continue

        if not trades_to_process:
            print(f"Nenhum trade para processar neste registro: {trade_data}")
            continue

        for trade_info in trades_to_process:
            # Adiciona um bloco try/except para lidar com dados malformados
            try:
                # Adiciona uma verificação de tipo para garantir que trade_info seja um dicionário
                if not isinstance(trade_info, dict):
                    print(f"Formato de dados inesperado, esperado um dicionário, mas recebido {type(trade_info)}: {trade_info}")
                    continue

                # Realiza as transformações
                trade_price = trade_info.get('p', 0.0)
                trade_volume = trade_info.get('v', 0)
                total_value = trade_price * trade_volume
    
                # Usa um ID único para cada item na lista, combinado com o eventID do Kinesis
                trade_id = f"{record['eventID']}-{trade_info.get('t')}"
                
                # Adapta para o caso de 'conditions' vir como string
                trade_conditions = trade_info.get('c', trade_info.get('conditions'))
                if isinstance(trade_conditions, str):
                    trade_conditions = trade_conditions.split(',')
                
                # Constrói o objeto de saída com tratamento para valores nulos/ausentes
                processed_record = {
                    'trade_id': trade_id,
                    'trade_symbol': trade_info.get('s') or "UNKNOWN",
                    'trade_timestamp': str(trade_info.get('t') or 0),
                    'trade_price': str(trade_price),
                    'trade_volume': str(trade_volume),
                    'total_value': str(total_value),
                    'ingestion_time': str(trade_info.get('ingestion_timestamp') or ""),
                    # Converte a lista de condições para string JSON para garantir compatibilidade com DynamoDB
                    'trade_conditions': json.dumps(trade_conditions or [])
                }
                
                # Remove chaves com valores de string vazia do dicionário
                processed_record = {k: v for k, v in processed_record.items() if v is not None and v != ""}
    
                processed_records.append(processed_record)
            except Exception as e:
                print(f"Erro ao processar um registro: {e}")
                continue

    if processed_records:
        with table.batch_writer() as batch:
            for rec in processed_records:
                try:
                    batch.put_item(
                        Item=rec
                    )
                except Exception as e:
                    print(f"Erro ao inserir item no DynamoDB: {e}")
                    
    return {
        'statusCode': 200,
        'body': json.dumps(f"Processed {len(processed_records)} records and sent to DynamoDB.")
    }
