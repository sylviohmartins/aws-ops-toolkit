# Adicionar uma nova operação

Objetivo: adicionar regra de incidente sem editar controller, JobCoordinator ou AWS client configuration.

## Passos

1. criar request/modelos específicos somente se necessários;
2. criar uma classe que implemente Workflow;
3. declarar type estável e único;
4. declarar resources(parameters);
5. validar parâmetros no próprio workflow;
6. implementar plan paginado/bounded;
7. implementar proposal para dry-run;
8. implementar execute usando JobContext para efeitos guardados;
9. registrar a classe como bean Spring condicionado a toolkit.operations.enabled;
10. adicionar teste focado da regra.

Exemplo:

~~~java
@Component
@ConditionalOnProperty(name = "toolkit.operations.enabled", havingValue = "true")
final class RepairExampleWorkflow implements Workflow {
    @Override
    public String type() { return "repair-example"; }

    @Override
    public Set<String> resources(JsonNode parameters) {
        return Set.of(DynamoWorkflow.required(parameters, "table"));
    }

    @Override
    public void validate(JobRequest request) {}

    @Override
    public Page plan(JobContext context, int segment, String cursor) throws Exception {
        // retornar uma página bounded
    }

    @Override
    public Proposal proposal(JsonNode parameters, SqliteJournal.Task task) {
        // before/after sanitizado
    }

    @Override
    public String execute(JobContext context, SqliteJournal.Task task) throws Exception {
        // efeitos somente via infraestrutura guardada
    }
}
~~~

RuntimeConfiguration descobre Workflow beans e os incorpora ao catálogo; type duplicado falha cedo.

## Não alterar

- JobController;
- JobCoordinator para registrar type;
- AwsClientConfiguration;
- retry/backpressure;
- journal/checkpoint;
- CSV engine;
- auth/STS.

Se a nova regra exigir editar várias dessas partes, reavaliar a arquitetura.
