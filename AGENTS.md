# ShopSphere — Guard Rails para agentes de código (Codex)

> **Aplicação:** instruções para qualquer agente que leia ou modifique este repositório. Leia também `SHOPSPHERE_CODEX_CONTEXT.md`. Estas instruções não autorizam mudanças por si só: a tarefa explícita do utilizador define o escopo.

## Estado atual do projeto

- ShopSphere está em desenvolvimento e ainda não opera em produção, conforme esclarecido pelo utilizador em 2026-10-27.
- Planeje as próximas entregas para o ambiente de desenvolvimento e o comportamento final pretendido. Não acrescente tratamento de registros legados, backfills de produção, janelas de manutenção, drenagem de tráfego ou estratégias de rollout apenas por hipótese.
- Só planeje essas medidas se o utilizador indicar uma necessidade concreta ou se o ambiente efetivamente inspecionado a demonstrar.
- Esta orientação não autoriza apagar dados/testes, sobrescrever trabalho existente ou editar migrations já aplicadas; continuam valendo as regras específicas abaixo.

## 1. Princípio fundamental: alterações mínimas e justificadas

- Execute **somente** a tarefa explicitamente solicitada. Prefira o menor diff que resolva o problema.
- **Não** faça refatorações oportunistas, limpeza de código, renomeações, reorganização de pacotes, formatação em massa, upgrades de dependências ou alterações de arquitetura sem pedido explícito.
- Não introduza funcionalidades, endpoints, campos, tabelas, integrações, abstrações, padrões ou bibliotecas apenas porque parecem úteis.
- Não modifique arquivos sem relação direta com a tarefa. Se uma alteração adjacente for indispensável, explique a dependência antes de avançar quando o impacto não for trivial.
- Preserve interfaces públicas, contratos HTTP, payloads, códigos de resposta, eventos, tópicos, filas, nomes de tabelas e comportamento existente, salvo solicitação explícita.
- Não reescreva um módulo inteiro para corrigir um problema localizado.

## 2. Fontes de verdade e resolução de divergências

Ordem de prioridade:
1. Instrução explícita mais recente do utilizador e requisitos da tarefa.
2. Código, testes, configuração, migrations e convenções **efetivamente existentes** no repositório.
3. `SHOPSPHERE_CODEX_CONTEXT.md`, como contexto de decisões e funcionalidades planejadas.
4. Recomendações gerais de engenharia.

- O documento de contexto não é inventário verificado do código: **não presuma que classes, endpoints, migrations ou serviços já existam**.
- Antes de editar, localize a implementação relevante e leia os arquivos próximos, os testes e as configurações pertinentes.
- Se houver divergência entre documentação e implementação, não altere o código automaticamente para «alinhar». Informe a divergência e peça orientação quando afetar contrato, dados ou arquitetura.
- Não invente nomes de propriedades, enums, endpoints, contratos Feign, eventos ou esquemas. Confirme-os no repositório.

## 3. Procedimento obrigatório antes de alterar código

1. Resuma o objetivo da tarefa em uma ou duas frases.
2. Identifique os arquivos afetados e a menor mudança necessária.
3. Verifique chamadas e dependências desses arquivos para evitar regressões.
4. Consulte os testes existentes e determine quais devem ser executados.
5. Se a tarefa for ambígua e uma decisão puder alterar contratos, persistência, segurança ou comportamento, **pergunte antes de implementar**.
6. Para tarefas pequenas e inequívocas, implemente diretamente, sem criar etapas artificiais.

## 4. Ações que exigem aprovação explícita

Não execute sem autorização específica do utilizador:

- Apagar arquivos, diretórios, migrations, testes, dados ou funcionalidades.
- Modificar migrations Flyway já aplicadas; fazer `DROP`, `TRUNCATE`, `DELETE` em massa ou reset de banco.
- Alterar schema, constraints, tipos de colunas ou regras de integridade fora do escopo solicitado.
- Modificar contratos externos: rotas, DTOs públicos, nomes de eventos, routing keys, exchanges, filas e tópicos.
- Alterar autenticação/autorização, permissões, exposição de endpoints, política de CORS ou regras de validação de JWT sem pedido explícito.
- Atualizar Spring Boot, Spring Cloud, Java, Gradle, plugins ou dependências sem necessidade demonstrada e aprovação para mudanças de versão.
- Adicionar serviços, bancos, brokers, infraestrutura, módulos ou dependências novas quando uma solução local atende à tarefa.
- Executar comandos destrutivos ou irreversíveis, como `git reset --hard`, `git clean -fd`, `git push --force`, exclusão de recursos em cloud ou alterações em ambientes compartilhados.
- Fazer deploy, publicar artefatos, abrir PRs ou enviar alterações remotas, salvo quando explicitamente solicitado. Commits locais de conclusão de microtarefas seguem a autorização permanente abaixo.

Se a tarefa explicitamente requer uma dessas ações, limite-se exatamente ao que foi autorizado. Se surgir uma ação destrutiva adicional não prevista, solicite confirmação.

### Commit obrigatório ao concluir microtarefas

- Por autorização permanente do utilizador, realize sempre um commit local após concluir cada microtarefa, inclusive documental, sem solicitar nova confirmação.
- Antes do commit, revise o diff e execute as validações pertinentes; declare eventuais limitações e não marque uma microtarefa incompleta como concluída.
- Inclua somente as alterações da microtarefa. Preserve alterações alheias, inclusive as já staged, e não use staging indiscriminado.
- Use uma mensagem que descreva a entrega e informe o hash do commit na resposta final. Se o commit falhar, informe a falha sem afirmar que foi criado.
- Esta autorização não inclui push, publicação, deploy ou outras ações remotas.

## 5. Preservação do trabalho existente

- Não sobrescreva mudanças não commitadas de outras pessoas. Inspecione `git status` e o diff relevante antes de editar, quando houver acesso ao Git.
- Não reverta nem reformate código fora do escopo.
- Não altere snapshots, fixtures ou testes para mascarar falhas de produção.
- Não remova verificações de segurança ou validações para «fazer os testes passarem».
- Não desative testes, lint, verificações de build, Flyway ou validação do Hibernate para ocultar problemas.
- Não introduza valores secretos, credenciais reais, tokens ou dados pessoais em código, logs ou documentação.

## 6. Regras de arquitetura do ShopSphere

- Respeite os limites entre **API Gateway MVC, Catalog, Cart, Order** e serviços futuros. Não crie dependências de banco entre microsserviços.
- Mantenha o **Spring Cloud Gateway Server Web MVC**; não migre para WebFlux/Reactive sem solicitação.
- Utilize Java/Spring Boot e o sistema de build realmente configurados em cada módulo; não misture Groovy DSL e Kotlin DSL sem verificar.
- O Gateway é o ponto de entrada externo; comunicação interna entre serviços pode ser direta, com endereços configuráveis.
- Para autenticação, valide JWT no serviço que protege o recurso; não confie exclusivamente na validação do Gateway.
- Identidade do utilizador deve vir do principal autenticado, não de `customerId` fornecido arbitrariamente pelo cliente. Só equipare `customerId` ao `sub` do Keycloak quando essa for a convenção confirmada do projeto.
- Use o token do utilizador em chamadas downstream apenas quando o serviço atua em nome dele; use credenciais de serviço quando apropriado. Não encaminhe tokens indiscriminadamente.
- Preserve a propriedade dos dados: Catalog é fonte de verdade para dados atuais dos produtos; Cart guarda seleção e quantidades; Order guarda snapshot de nome, preço, quantidade, moeda e total acordados.
- Não implemente chamadas entre serviços por acesso direto às tabelas de outro serviço.
- Não acrescente Inventory, Payment, Saga, Outbox ou mensageria ao fluxo atual apenas porque aparecem como evolução planejada no documento de contexto.

## 7. Persistência e Flyway

- O Flyway é a fonte de verdade para mudanças de schema. Para alterações novas, crie uma **nova migration versionada** com o próximo número disponível; nunca edite uma migration já aplicada.
- Confirme nomes de tabelas, colunas, constraints, enums e mapeamentos JPA antes de escrever SQL.
- Prefira migrations incrementais, seguras e compatíveis com os dados existentes. Se uma alteração exigir backfill, plano de rollback ou janela de manutenção, explicite isso.
- Não execute migrations contra bases remotas ou compartilhadas sem autorização.
- Preserve precisão monetária com `BigDecimal` e `NUMERIC`, nunca `double`/`float` para valores financeiros.
- Não introduza foreign keys entre bancos de microsserviços.
- Ao modificar entidades, considere cascatas, `orphanRemoval`, concorrência e `@Version` sem presumir que a versão do agregado muda quando apenas os filhos mudam.

## 8. APIs, Feign, segurança e integrações

- Preserve URLs, verbos, códigos HTTP, formatos de erro e contratos já utilizados por outros módulos.
- Em `@FeignClient`, diferencie `name` (identificador lógico), `url` (host configurável) e `path` (caminho da API); não use paths como nomes de serviço.
- Configure timeouts e tratamento de falhas quando a tarefa envolver integrações; não introduza retries automáticos em operações não idempotentes.
- Não use `permitAll()` ou desabilite autenticação para contornar erros 401/403.
- Nunca registre JWTs, passwords, secrets ou dados sensíveis em logs.
- Em operações de checkout, não confie em preço, total ou identidade enviados pelo frontend; revalide dados necessários e preserve snapshots no Order.
- Não mantenha transações de banco abertas durante chamadas HTTP externas, salvo justificativa explícita e aceite do risco.
- Se implementar publicação de eventos relacionada a uma transação, trate o risco de inconsistência entre commit no banco e publicação no broker; não prometa entrega exatamente uma vez.

## 9. Qualidade, testes e validação

- Adicione ou ajuste **somente os testes relevantes** para a mudança solicitada.
- Execute primeiro testes focados no módulo alterado; amplie a validação quando houver impacto transversal.
- Quando possível, execute compilação e testes com o wrapper do repositório (`./gradlew` ou `gradlew.bat`), sem alterar configurações globais.
- Se não puder executar testes por falta de Docker, serviços externos, credenciais ou infraestrutura, informe exatamente o que não foi validado. Não declare sucesso sem evidência.
- Não altere expectativas de testes para acomodar uma regressão; corrija a causa.
- Mantenha tratamento de erros, validações e observabilidade compatíveis com o padrão já presente.

## 10. Resposta final obrigatória do agente

Ao concluir cada tarefa, apresente de forma objetiva:

1. **O que mudou:** comportamento e motivo.
2. **Arquivos alterados:** caminhos concretos.
3. **Validação:** comandos executados e resultado; ou limitações.
4. **Impactos e riscos:** migrações, mudanças de contrato, compatibilidade, pendências.
5. **O que não foi alterado:** quando relevante para deixar claro que o escopo foi respeitado.

Não afirme que algo foi implementado, testado, executado ou publicado se não foi. Não esconda falhas de build ou testes.

## 11. Checklist de autocontrolo antes de finalizar

- [ ] A mudança atende exatamente ao pedido?
- [ ] O diff é mínimo e restrito aos arquivos necessários?
- [ ] Não houve alteração involuntária de API, dados, segurança ou infraestrutura?
- [ ] O código segue as convenções reais do módulo?
- [ ] As migrations existentes permanecem intactas?
- [ ] Testes relevantes foram executados ou as limitações foram declaradas?
- [ ] Nenhuma credencial ou informação sensível foi exposta?
- [ ] A resposta final distingue claramente fatos verificados de suposições?

**Regra de desempate:** quando houver dúvida entre uma mudança maior «mais elegante» e uma correção pequena suficiente, escolha a correção pequena. Quando uma decisão relevante não estiver especificada, não adivinhe: pergunte.
