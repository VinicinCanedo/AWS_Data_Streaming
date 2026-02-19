# 📊 Serverless Datalake com AWS — Streaming de Dados da Bolsa de Valores

Este projeto demonstra como construir uma arquitetura **serverless** para ingestão e processamento de dados da bolsa de valores dos EUA em tempo real, utilizando serviços gerenciados da AWS. A proposta é criar uma pipeline escalável, eficiente e de baixo custo, com foco total na lógica de negócios.

---
📊 Fonte de Dados (Data Source)
O projeto utiliza uma API de mercado financeiro para o consumo de dados via WebSockets. A arquitetura foi desenhada para lidar com as seguintes características da fonte:

Streaming em Tempo Real (Push Data): Diferente de APIs baseadas em polling (onde o sistema precisa pedir a informação), utilizamos o modelo Push, onde o servidor envia os dados instantaneamente assim que uma transação ocorre, garantindo baixíssima latência.

Comportamento Multi-Ativo: O sistema processa ativos de diferentes naturezas:

Ações (Stocks): Dados centralizados em bolsas (ex: NYSE).

Forex e Criptomoedas: Como são mercados descentralizados, o pipeline trata atualizações informativas de preço. Quando um registro possui volume zero, o sistema o interpreta como uma atualização de cotação (tick) e não necessariamente uma transação executada.

Eficiência de Banda (Batching): A API agrupa múltiplas negociações em um único payload JSON para otimizar o tráfego. O código de ingestão está preparado para processar essas listas de registros de forma eficiente antes do envio ao AWS Kinesis.

💡 Contexto de Negócio e Arquitetura
A ideia central do projeto é criar uma infraestrutura robusta de Data Ingestion capaz de escalar conforme o volume do mercado financeiro mundial.

Ingestão de Alta Frequência: Capturar e padronizar fluxos de dados heterogêneos para análise posterior ou dashboards em tempo real.

Resiliência e Segurança: O sistema implementa um controle de sessão rigoroso para respeitar a política de conexão única por chave de API, evitando quedas de serviço e garantindo a integridade do fluxo de dados.

Processamento Escalável: Ao utilizar o AWS Kinesis como porta de entrada, o projeto demonstra a capacidade de desacoplar a fonte de dados (API) dos consumidores (bancos de dados, lambdas ou analytics), permitindo crescimento horizontal.
---
## 🚀 Visão Geral

A arquitetura foi desenhada para consumir dados de trade em tempo real e armazená-los de forma otimizada, sem a necessidade de gerenciar servidores ou infraestrutura complexa. Utilizando o modelo **Serverless Datalake**, o projeto explora o poder da nuvem para entregar:

- Alta escalabilidade  
- Baixo custo operacional  
- Alta disponibilidade  
- Foco total na lógica de dados  

---

## 🧰 Tecnologias Utilizadas

| Serviço / Ferramenta | Finalidade |
|----------------------|------------|
| **Java**             | Producer local para envio dos dados |
| **AWS Kinesis**      | Ingestão e processamento em tempo real |
| **AWS Lambda**       | Processamento e persistência dos dados |
| **AWS DynamoDB**     | Armazenamento NoSQL escalável |
| **IAM**              | Controle de acesso entre serviços |
| **AWS CLI**          | Provisionamento e automação |

---

## 🛠️ Passo a Passo para Reproduzir o Projeto

### 1️⃣ Criar uma conta no Finnhub e obter a API Key

- Acesse [https://finnhub.io](https://finnhub.io) e crie uma conta gratuita.
- Após o login, gere sua **API_KEY**.
- No projeto Java, insira essa chave no arquivo `application.properties`:

```properties
finnhub.api.key=YOUR_API_KEY
```

- Compile o projeto e gere o `.jar` com sua aplicação de streaming.

---

### 2️⃣ Instalar e configurar o AWS CLI

- Baixe o AWS CLI: [https://docs.aws.amazon.com/cli/latest/userguide/install-cliv2.html](https://docs.aws.amazon.com/cli/latest/userguide/install-cliv2.html)
- Configure com suas credenciais:

```bash
aws configure
```

---

### 3️⃣ Criar um Kinesis Data Stream

- No console da AWS ou via CLI, crie um stream chamado:

```bash
stream-finnhub
```

- Esse será o canal de ingestão dos dados de trade.

---

### 4️⃣ Criar uma função IAM para o Lambda

- Vá até o IAM e crie uma função chamada:

```text
FinnhubStreamProcessorRole
```

- Anexe a política gerenciada:

```text
AWSLambdaKinesisExecutionRole
```

- Crie uma política personalizada chamada `PolicyWriteDynamoDB` com as permissões:

```json
{
  "Effect": "Allow",
  "Action": [
    "dynamodb:PutItem",
    "dynamodb:BatchWriteItem"
  ],
  "Resource": "arn:aws:dynamodb:<REGIAO>:<ID_CONTA>:table/ProcessedTradesDynamoDB"
}
```

- Anexe essa política à função.

---

### 5️⃣ Criar a tabela no DynamoDB

- Nome sugerido: `ProcessedTradesDynamoDB`
- Chave de partição obrigatória: `trade_id` (tipo: String)

> Você pode usar outro nome para a tabela, mas o campo `trade_id` deve existir como chave primária.

---

### 6️⃣ Criar a função Lambda

- Crie uma função Lambda com o runtime Python 3.13.
- Configure o gatilho como o stream `stream-finnhub`.
- Use o código `lambda_function.py` presente neste repositório.
- Adicione uma variável de ambiente chamada:

```text
DYNAMODB_TABLE_NAME = ProcessedTradesDynamoDB
```

- Vincule a função à role `FinnhubStreamProcessorRole`.

---

### 🧾 Observações

- O arquivo `consumer.py` está presente apenas para fins ilustrativos e **não precisa ser executado**.
- O projeto foi pensado para operar durante o horário de mercado, otimizando custos com arquitetura serverless.

---

## 📚 Fontes e Inspiração

- [Pixegami - YouTube](https://www.youtube.com/watch?v=CjVPMocEECM)  
- [AWS Lambda Stream Processing](https://github.com/aws-samples/lambda-refarch-streamprocessing)  
- [Kinesis Data Analytics Blueprints](https://github.com/aws-samples/amazon-kinesis-data-analytics-blueprints/tree/main/apps/java-datastream/kds-to-s3-datastream-java)

---
