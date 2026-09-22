# Adicionar uma tabela DynamoDB

Há dois caminhos: typed para tabelas recorrentes e schemaless para investigação ad hoc.

## Typed

1. criar XxxDynamoItem;
2. definir TableSchema<T>;
3. criar DynamoTableDescriptor<T> com logical name/chave/índices;
4. adicionar toolkit.dynamodb.tables.<logical-name> no ambiente;
5. pedir DynamoTableGatewayFactory.create(descriptor);
6. criar mapper somente se domain divergir semanticamente;
7. usar workflow/adapter guardado para mutations.

~~~java
var descriptor = new DynamoTableDescriptor<>(
    "payments",
    TableSchema.fromBean(PaymentDynamoItem.class),
    "paymentId",
    Set.of("status-index"));

DynamoTableGateway<PaymentDynamoItem> table = gatewayFactory.create(descriptor);
~~~

~~~yaml
toolkit:
  dynamodb:
    tables:
      payments: dev-payments
~~~

Trocar o nome físico exige apenas configuração.

## Schemaless

Use Document/AttributeValue para investigação ad hoc, mantendo paginação, budgets, allowlist e guardrails.

## Não criar

- novo DynamoDbClient;
- retry próprio;
- paginação própria;
- executor próprio;
- table name hardcoded;
- generic write gateway que bypassa ExecutionPolicy/ledger.
