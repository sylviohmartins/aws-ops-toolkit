# Contribuindo com aws-ops-toolkit

Mantenha cada alteração pequena, reproduzível e revisável. O repositório inclui uma fundação executável e documentação de arquitetura alvo; ao contribuir, indique claramente qual comportamento foi implementado e qual continua planejado.

## Branches e commits

Use branches de curta duração, criadas a partir da branch principal atualizada, no formato `<tipo>/<assunto>`. Exemplos: `feat/sqs-inspection`, `fix/checkpoint-order` e `docs/credential-refresh`. Uma branch deve corresponder a uma mudança coerente. Não inclua nomes de clientes, dados internos de incidentes ou credenciais no nome.

Antes de trabalhar, confira `git status` e preserve alterações que já existirem. Atualize a base sem sobrescrever trabalho local; use merge ou rebase conforme a situação. Não reescreva histórico compartilhado sem coordenação, não faça force-push na principal e não misture reformatação ampla com correção funcional.

Adote [Conventional Commits 1.0.0](https://www.conventionalcommits.org/en/v1.0.0/): `tipo(escopo): descrição`. Escopo é opcional. Use `feat`, `fix`, `docs`, `test`, `refactor`, `build`, `ci` ou `chore` conforme o efeito. Exemplos:

```text
feat(sqs): add explicit consent for inspection
fix(checkpoint): persist cursor after page confirmation
docs(aws): clarify temporary credential renewal
ci: verify Java 25 and local smoke on Windows
```

O título deve explicar o resultado; o corpo registra motivação, decisões relevantes e limitações. Mudanças incompatíveis usam `!` ou footer `BREAKING CHANGE:` e descrevem migração. Faça commits que representem passos coerentes e evite mensagens como `wip`, `ajustes` ou `fix final` no histórico integrado.

Revise o diff antes de adicionar arquivos ao índice. Prefira caminhos explícitos em `git add`; confira `git diff --cached` e `git diff --cached --check` antes do commit. Artefatos em `target/`, relatórios de operações, checkpoints, logs e configuração pessoal não pertencem ao Git.

## Ambiente e validação local

Use JDK **25**, sem recursos preview, com `JAVA_HOME` apontando para essa instalação. Use o Maven Wrapper versionado no repositório; ele seleciona a versão de Maven aprovada no projeto. Dependências, plugins e versões estão no `pom.xml` e nas propriedades do wrapper.

No PowerShell, a partir da raiz:

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress verify -Pstatic-analysis
.\scripts\smoke.ps1
```

Em Linux/macOS, a validação Maven equivalente é:

```sh
./mvnw --batch-mode --no-transfer-progress verify -Pstatic-analysis
```

O smoke atual é específico de Windows: inicia o JAR localmente, usa token temporário gerado pelo próprio script e exercita somente a operação sintética. Valida autenticação, origem, bloqueio de escrita, admissão, CSV, pausa, queda/retomada e cancelamento. Não precisa de login AWS e não é um ensaio de produção.

`verify` inclui testes e verificação de formato; o perfil `static-analysis` acrescenta a análise estática configurada no POM. Para corrigir apenas o formato Java, execute `./mvnw spotless:apply` ou `.\mvnw.cmd spotless:apply`, revise as alterações e execute novamente a validação. Não desabilite checks para obter um resultado verde.

Adicione testes quando houver comportamento ou risco que precise ser demonstrado. Priorize transições de estado, paginação, falhas parciais, limites de concorrência, idempotência, autenticação e persistência. Uma mudança documental simples não exige testes artificiais; registre que a validação aplicável foi documental.

## Pull requests e revisão

Abra um PR para a principal, usando o [modelo do repositório](.github/pull_request_template.md). Comece pelo problema concreto e pelo comportamento resultante, depois indique evidências de validação e limitações. Use Draft enquanto houver decisões ou implementação pendentes. O título do PR também deve seguir Conventional Commits, especialmente quando for usado como mensagem de squash.

Solicite revisão antes de integrar. Mudanças em autorização, produção, escrita AWS, checkpoints, retries ou concorrência precisam de revisão que considere efeitos remotos e recuperação após falhas. Um build verde não substitui essa análise. Resolva comentários e aguarde o CI da revisão final antes de fazer merge.

Prefira squash merge para um PR pequeno que represente uma única mudança; preserve commits separados quando eles forem úteis para entender uma evolução maior. Depois da integração, remova a branch concluída e atualize sua base local. Não use merge como autorização para executar uma operação AWS.

Proteção da branch principal, revisão obrigatória e status checks obrigatórios são configurações a aplicar no GitHub pelo responsável pelo repositório. Estes arquivos **não configuram nem comprovam** que essas proteções estão ativas.

## Dados, segurança e operações AWS

- Use fixtures sintéticas. Não comite nem anexe a PRs dumps produtivos, receipt handles reais, payloads restritos, tokens, access keys, arquivos de profile ou relatórios de incidentes.
- Mantenha AWS desabilitada nos testes padrão e no CI. Não adicione credenciais AWS, ambientes de produção ou assunção de role ao workflow de verificação.
- Preserve os guardrails: identidade e recurso explícitos, escrita negada por padrão, dry-run, limites, cancelamento e trilhas de auditoria. A autorização deve ocorrer antes do efeito remoto.
- Testes que precisem de AWS real devem ser separados, executados somente em ambiente autorizado e ter custos, escopo e limpeza definidos. Testar em produção exige o processo corporativo específico; não faz parte da validação deste repositório.
- Não publique detalhes de vulnerabilidades ou segredos em issues públicas. Use o canal privado aprovado pela organização; em exposição acidental, acione o processo corporativo de resposta e rotação.

Documente diferenças entre comportamento local, emulador e serviço AWS. Simulação bem-sucedida não prova limites de throughput, consistência ou permissões reais.

## Manutenção do CI

[`.github/workflows/verify.yml`](.github/workflows/verify.yml) executa um job Windows com Temurin 25, Maven Wrapper, análise estática e smoke local. É disparado por PR, push em `main` e execução manual. Usa somente `contents: read`, não solicita OIDC e não persiste credenciais de checkout. Maven e o JDK podem ser baixados; “sem AWS” não significa execução sem acesso à rede.

As actions oficiais estão fixadas em SHAs completos, conferidos nas tags dos repositórios oficiais em **20/09/2026**: [checkout v7.0.1](https://github.com/actions/checkout/releases/tag/v7.0.1) e [setup-java v6.0.1](https://github.com/actions/setup-java/releases/tag/v6.0.1). A fixação evita seguir uma tag mutável sem revisão, conforme a [orientação de segurança do GitHub Actions](https://docs.github.com/en/actions/reference/security/secure-use). Atualize SHA e comentário da versão juntos, revisando changelog, origem e o resultado do CI.

Java fica na linha `25` para receber atualizações dessa linha. O workflow não fixa a imagem inteira de `windows-latest`; registre versões relevantes nas evidências quando investigar diferenças entre execução local e CI. O primeiro resultado remoto só existe após publicar o workflow e executá-lo no GitHub.
