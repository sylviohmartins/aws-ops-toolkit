## Problema e resultado

<!-- Descreva o problema concreto, o comportamento resultante e o motivo da mudança. -->

## Validação

<!-- Informe comandos executados, resultados e limitações. Não anexe dados reais ou segredos. -->

- [ ] `mvnw verify -Pstatic-analysis` passou, ou a validação não se aplica e o motivo está descrito.
- [ ] Smoke local no Windows passou quando a mudança afeta o comportamento executável.
- [ ] Testes relevantes cobrem o comportamento alterado e suas falhas; mudanças documentais estão identificadas.

## Impacto operacional

<!-- Se aplicável: efeitos AWS, autorização, idempotência, limites, cancelamento, checkpoint, compatibilidade e recuperação. Remova este comentário quando não se aplicar. -->

## Revisão

- [ ] O diff contém somente mudanças relacionadas ao objetivo do PR.
- [ ] Documentação distingue funcionalidades implementadas de arquitetura alvo.
- [ ] Nenhum segredo, dado produtivo, log restrito ou artefato de operação foi incluído.
- [ ] A validação padrão não usa credenciais AWS nem executa testes em produção.
- [ ] Mudanças incompatíveis e migração estão descritas quando existirem.

<!-- Aguarde revisão e CI da versão final antes de integrar. Um PR aprovado não autoriza execução AWS. -->
