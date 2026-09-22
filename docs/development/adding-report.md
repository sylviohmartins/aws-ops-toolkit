# Adicionar ou alterar relatório

Relatório é projection, não entity nem DTO de integração.

## Novo CSV

1. criar XxxReportRow se a visão tiver razão própria para mudar;
2. declarar List<CsvColumn<XxxReportRow>>;
3. obter CsvReportWriter pela factory;
4. alimentar Iterator ou Session incrementalmente;
5. devolver ReportResult/evidência;
6. testar escaping, nulls e formula-like cells.

~~~java
var columns = List.of(
    new CsvColumn<PaymentReportRow>("paymentId", PaymentReportRow::paymentId),
    new CsvColumn<PaymentReportRow>("status", PaymentReportRow::status));

var writer = csvFactory.create(columns);
writer.write(rowsIterator, targetWriter, true);
~~~

## Adicionar coluna

Alterar somente projection/schema. Não alterar Dynamo adapter, controller global, AWS clients ou batching engine.

## Grandes volumes

Nunca construir uma lista com todas as linhas. Escrever registro/page → report → flush periódico → checkpoint.

Commons CSV cuida do escaping; CsvReportWriter cuida da policy e streaming.
