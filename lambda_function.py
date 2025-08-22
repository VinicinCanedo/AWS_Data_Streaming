import json
import base64
import boto3
import os

# Initializes the Boto3 client for DynamoDB
dynamodb = boto3.resource('dynamodb')
# Get the table name from an environment variable
table_name = os.environ.get('DYNAMODB_TABLE')
if not table_name:
    # Log the error instead of failing abruptly
    print("Error: 'DYNAMODB_TABLE' environment variable not defined.")
    table = None
else:
    # Assign the 'table' variable to the DynamoDB table object
    table = dynamodb.Table(table_name)

def lambda_handler(event, context):
    if table is None:
        return {
            'statusCode': 500,
            'body': json.dumps("Configuration Error: 'DYNAMODB_TABLE' environment variable not defined.")
        }
        
    processed_records = []

    for record in event['Records']:
        # Decode the Kinesis base64 payload
        try:
            payload = base64.b64decode(record['kinesis']['data'])
            data_string = payload.decode('utf-8')
        except (base64.binascii.Error, UnicodeDecodeError) as e:
            print(f"Error decoding Kinesis data: {e}")
            continue
        
        # Convert the JSON string to a Python dictionary
        try:
            trade_data = json.loads(data_string)
        except json.JSONDecodeError:
            print(f"Invalid JSON: {data_string}")
            continue

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

        if not trades_to_process:
            print(f"No trades to process in this record: {trade_data}")
            continue

        for trade_info in trades_to_process:
            # Add a try/except block to handle malformed data
            try:
                # Add a type check to ensure trade_info is a dictionary
                if not isinstance(trade_info, dict):
                    print(f"Unexpected data format, expected a dictionary, but received {type(trade_info)}: {trade_info}")
                    continue

                # Perform the transformations
                trade_price = trade_info.get('p', 0.0)
                trade_volume = trade_info.get('v', 0)
                total_value = trade_price * trade_volume
    
                # Use a unique ID for each item in the list, combined with the Kinesis eventID
                trade_id = f"{record['eventID']}-{trade_info.get('t')}"
                
                # Adapt for the case where 'conditions' comes as a string
                trade_conditions = trade_info.get('c', trade_info.get('conditions'))
                if isinstance(trade_conditions, str):
                    trade_conditions = trade_conditions.split(',')
                
                # Build the output object with handling for null/missing values
                processed_record = {
                    'trade_id': trade_id,
                    'trade_symbol': trade_info.get('s') or "UNKNOWN",
                    'trade_timestamp': str(trade_info.get('t') or 0),
                    'trade_price': str(trade_price),
                    'trade_volume': str(trade_volume),
                    'total_value': str(total_value),
                    'ingestion_time': str(trade_info.get('ingestion_timestamp') or ""),
                    # Convert the list of conditions to a JSON string to ensure compatibility with DynamoDB
                    'trade_conditions': json.dumps(trade_conditions or [])
                }
                
                # Remove keys with empty string values from the dictionary
                processed_record = {k: v for k, v in processed_record.items() if v is not None and v != ""}
    
                processed_records.append(processed_record)
            except Exception as e:
                print(f"Error processing a record: {e}")
                continue

    if processed_records:
        with table.batch_writer() as batch:
            for rec in processed_records:
                try:
                    batch.put_item(
                        Item=rec
                    )
                except Exception as e:
                    print(f"Error inserting item into DynamoDB: {e}")
                    
    return {
        'statusCode': 200,
        'body': json.dumps(f"Processed {len(processed_records)} records and sent to DynamoDB.")
    }