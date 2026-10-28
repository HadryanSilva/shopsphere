# Inventory — análise inicial e plano incremental

## Estado verificado na análise inicial

Esta tabela descreve o estado inicial, anterior à microtarefa 30. As regras
de negócio ainda não foram implementadas. A proposta completa de regras e
contratos foi aprovada pelo utilizador na microtarefa 29 e passa a orientar
as entregas seguintes.

| Área | Situação atual |
|---|---|
| Build | Java 25, Spring Boot 4.1.1, Gradle 9.8.1 e Groovy DSL |
| Dependências | JPA, PostgreSQL, MVC, Bean Validation, OAuth2 Resource Server, Springdoc e Lombok |
| Aplicação | Apenas `InventoryServiceApplication`, no package `br.com.hadryan.shosphere.inventory` |
| Configuração principal | `application.properties` define somente o nome do serviço |
| Persistência | Sem entidades, repositórios, migrations ou dependências Flyway |
| Segurança | Sem SecurityFilterChain próprio ou regras de autorização de domínio |
| API | Sem controllers, DTOs, casos de uso ou contratos Inventory |
| Integrações | Sem clientes HTTP, consumidores, publicadores ou ligação ao Order |
| Operação | Sem porta específica, perfil dev, instrumentação própria ou Actuator no módulo |
| Testes | Um teste de contexto com perfil test; datasource desativado e decoder JWT simulado |
| Infra local | `init.sql` prevê `inventory_db`; isso não comprova que o banco exista no volume atual |

O teste de referência foi executado com
`.\gradlew.bat test --rerun-tasks --offline --no-daemon`: um teste aprovado,
sem falhas ou ignorados. Isso valida apenas a montagem atual do contexto,
sem PostgreSQL, Keycloak ou regras de estoque. Não foi validado arranque real
do serviço com dependências externas.

O Compose contém PostgreSQL e Keycloak; não há broker configurado. O Gateway
roteia Catalog, Cart e Order e não possui rotas Inventory. Order consulta Cart
e Catalog, persiste snapshots e começa em `PENDING_STOCK`, mas não reserva
estoque. O enum `OUT_OF_STOCK` do Catalog não contém quantidades disponíveis.

## Microtarefa 30 concluída

Inventory possui agora perfil dev na porta 8084, datasource/JWT externalizáveis,
suporte Flyway PostgreSQL e Hibernate em validate, mantendo as versões do build.
Os quatro testes focados sem infraestrutura passaram. A suíte completa com
PostgreSQL local aprovou cinco testes, sem falhas ou ignorados, incluindo duas
inicializações de Flyway/JPA em schema temporário sem tabelas de domínio.
Na entrega 30, o registro técnico de criação de schema do Flyway foi permitido;
a V1 de domínio foi acrescentada posteriormente na microtarefa 31.

O decoder JWT foi simulado nos testes; o arranque com servidor HTTP e Keycloak
real não foi validado nessa entrega. A autorização foi acrescentada na
microtarefa 32; endpoints de negócio continuam para as entregas seguintes.
Instruções em [TESTING.md](TESTING.md).

## Microtarefa 31 concluída

Foram acrescentados InventoryStock, InventoryStockRepository e
V1__create_inventory_stock.sql. A posição usa productId como PK, contadores
INTEGER com 0 <= reserved <= on_hand, versão otimista e timestamps. Disponível
é calculado, sem coluna persistida. Não há geração de ID diferente do produto,
FK para Catalog ou setters públicos de contadores.

Nove testes focados sem infraestrutura passaram. A suíte completa com
PostgreSQL local aprovou 18 testes, sem falhas ou ignorados: V1/JPA validate,
reinício preservando dados, limites, constraints SQL, rejeição de cadastro
duplicado sem sobrescrever saldo e de escrita com versão antiga. A migration
foi aplicada apenas nos schemas temporários dos testes. Não foram acrescentados
endpoints, ajustes, movimentos ou reservas.

## Microtarefa 32 concluída

Inventory valida JWT no próprio serviço e mapeia realm roles. Apenas os
caminhos/verbos aprovados são permitidos: ADMIN nas operações administrativas,
e INVENTORY_SERVICE mais azp shopsphere-order nas internas. O restante é negado.
Sessões são stateless, e erros MVC/filtros seguem Problem Details sem valores
sensíveis, preservando WWW-Authenticate nas respostas Bearer.

No Keycloak local foram criados a role INVENTORY_SERVICE e o cliente
confidencial shopsphere-order, com service account, escopo restrito e mapper
de audiência shopsphere-api. Flows de login de utilizador foram desativados no
novo cliente. Não houve alterações em clientes de outros módulos ou export de
secret. Detalhes e configuração reproduzível em [SECURITY.md](SECURITY.md).

Os testes focados passaram. A suíte completa com PostgreSQL e token real de
client_credentials aprovou 73 testes, sem falhas ou ignorados. A execução
inicial completa revelou controllers auxiliares duplicados no runner de
persistência; o filtro de TestComponent foi corrigido sem desativar testes ou
alterar o schema. Não há API de negócio ou ligação Order -> Inventory ainda.

## Microtarefa 33 concluída

Foi implementado POST /api/v1/inventory/items com ADMIN, validação do body,
ator derivado do JWT, chave UUID obrigatória e retorno 201/Location. O produto
é validado no Catalog via RestClient da stack existente, sem JWT downstream,
com timeouts e tratamento 404/502/503, fora da transação de banco.

A nova V2 cria inventory_movements para registro INITIAL/idempotência. V1
permanece intacta. Posição e registro inicial são gravados na mesma transação;
replay compara o payload original e devolve a posição atual sem consultar
Catalog. Corridas por chave/produto não duplicam gravações nem deixam posição
órfã ou chave consumida na tentativa revertida.

Os testes focados passaram. A suíte completa com PostgreSQL e o teste opt-in
de token Keycloak aprovou 120 testes, sem falhas ou ignorados. Também foi
verificada atualização de V1 com dados para V2 sem modificar os saldos antigos
ou fabricar movimentos históricos. Catalog foi simulado/servidor HTTP de teste;
não foi realizado um cadastro em banco de uso normal ou via Catalog real.

Contratos implementados, arquivos e limitações em [REGISTRATION.md](REGISTRATION.md).
GET de consulta fica para 35; ajustes para 34; reservas para suas etapas.

## Responsabilidades e escopo recomendado

Inventory deve ser a fonte de verdade para quantidades e reservas. Catalog
continua responsável por produto e estado comercial; Cart guarda seleção;
Order guarda o pedido e seus snapshots. IDs de produto/pedido são referências
lógicas, sem foreign keys ou leitura direta de bancos de outros serviços.

Escopo aprovado para a primeira entrega: saldo, consultas e ciclo de reservas no
próprio Inventory. Integrar o checkout somente depois de validar esse núcleo.
As regras e contratos aprovados estão no documento da microtarefa 29.
O transporte para integração com outros serviços continua fora desta etapa.

Modelo aprovado: uma posição de estoque por productId,
quantidades inteiras, sem armazéns múltiplos ou backorder. Disponível seria
saldo físico menos reservado, com ambos não negativos e reservado nunca maior
que físico. Reservar reduz apenas o disponível; confirmar consome o
físico e a reserva; liberar devolve o disponível. A implementação dessas regras
ocorre nas próximas microtarefas, sem alterar o checkout automaticamente.

Não atualizar automaticamente o status do Catalog a partir do saldo. Não
permitir que um cliente escolha livremente a identidade de um serviço para
operar reservas. Não adicionar RabbitMQ, Saga, Outbox ou integração Payment
como consequência implícita do desenvolvimento de Inventory.

## Decisões da microtarefa 29

Microtarefa concluída: a proposta completa foi aprovada pelo utilizador em
[contracts/inventory-v1-proposal.md](../contracts/inventory-v1-proposal.md).
O documento define reservas locais, acesso interno por identidade de serviço,
prazo de 15 minutos e job de expiração a cada 60 segundos, ambos configuráveis.
A aprovação não significa que API, schema, role ou cliente Keycloak já existam.

Decisões registradas no contrato aprovado; detalhes técnicos de implementação
serão fechados na entrega correspondente sem alterar silenciosamente essas regras:

- Escopo da primeira entrega e se reservas fazem parte dela.
- Unidade, limites numéricos, estoque único/múltiplo e política de saldo negativo.
- Cadastro inicial, validação de existência no Catalog e tratamento de produto inativo.
- Operações administrativas, reposição, redução de saldo e auditoria mínima.
- Consultas expostas, permissões administrativas e identidade de serviço para reservas.
- Identificador da reserva, vínculo ao pedido, estados e transições permitidas.
- Reserva de todos os itens ou parcial, política de duplicatas e quantidade inválida.
- Idempotência de operações mutáveis, comparação de payload e resposta em conflito.
- Expiração, liberação, confirmação tardia e reconciliação de reservas abandonadas.
- Rotas/DTOs/códigos HTTP, nomes de tabelas/constraints e estratégia de concorrência.
- Porta local livre e configuração de emissor/audiência, confirmando o Keycloak.

Não há rota ou tabela nova tratada como existente. Os contratos aprovados
estão documentados em `contracts/`. A escolha HTTP/eventos
para ligar Order ao Inventory fica numa decisão posterior específica.

## Microtarefas propostas, por dependência

Cada linha representa uma entrega separada. A análise inicial e a microtarefa
29 a 41 estão concluídas; reservas fazem parte do escopo aprovado.
A ligação ao checkout exige a decisão específica descrita ao final deste plano.

| Nº | Entrega | Depende de | Critério de conclusão |
|---|---|---|---|
| 29 | Definir regras e contratos mínimos | Análise inicial | Concluída: proposta completa aprovada, com exemplos de sucesso/erro e responsabilidades; sem alterar outros serviços. |
| 30 | Configurar arranque local e Flyway | 29 | Concluída: perfil dev, porta 8084, datasource/JWT configuráveis, Flyway PostgreSQL e Hibernate validate. Cinco testes aprovados com PostgreSQL local; sem tabelas de domínio ou aplicação em banco compartilhado. |
| 31 | Persistir a posição de estoque | 29–30 | Concluída: InventoryStock, repositório e V1 alinhados, checks/PK e versão otimista validados. Dezoito testes aprovados com PostgreSQL; sem FK para Catalog. |
| 32 | Definir segurança e erros da API | 29–30 | Concluída: JWT/roles/azp e Problem Details testados, cliente de serviço provisionado no Keycloak local; 73 testes aprovados com PostgreSQL e token real. |
| 33 | Cadastrar estoque e validar produto | 31–32 | Concluída: POST ADMIN, Catalog via RestClient sem novas dependências, V2/INITIAL e idempotência concorrente. Suíte completa com 120 testes aprovados. |
| 34 | Repor e ajustar saldo | 33 | Concluída: POST/GET ADMIN de ajustes, auditoria ADJUSTMENT, replay de snapshot e bloqueio transacional. Reservas/limites/rollback e concorrência validados com PostgreSQL; 134 testes aprovados e um teste opcional de token real ignorado. Sem nova migration. |
| 35 | Consultar saldo e disponibilidade, incluindo batch | 31–32 | Concluída: GET individual/batch ADMIN, 1–100 UUIDs distintos, ausência versus saldo zero, DTO existente e leitura sem efeitos. Doze testes focados HTTP/PostgreSQL aprovados. Consulta não promete reserva. |
| 36 | Persistir reservas e suas transições | 31 | Concluída: modelo de reserva/itens, quatro estados, vínculo ao cliente, versão e V3 com PK/FKs/checks. V1/V2 intactas; 30 testes focados de modelo/persistência/arranque aprovados. Sem endpoints ou efeitos de reserva no saldo; transições de negócio nas etapas 38–39. |
| 37 | Criar reserva idempotente | 35–36 | Concluída: PUT/GET internos com role e azp existentes, reserva atômica, replay por intenção sem renovar prazo, TTL padrão 15 minutos configurável. Rollback, 100 itens e concorrência validados com PostgreSQL. Suíte completa: 194 aprovados e um teste opcional de token real ignorado. Sem nova migration ou ligação ao checkout. |
| 38 | Liberar e confirmar reserva | 37 | Concluída: POSTs internos, matriz de estados/prazo, confirmação e devolução atômicas e idempotentes. Confirmação valida prazo após locks; liberação vencida marca EXPIRED. Concorrência, rollback e 100 itens validados com PostgreSQL; sem nova migration. Job na etapa 39. |
| 39 | Tratar expiração e reconciliação | 38 | Concluída: job padrão PT60S/lote 50 configuráveis, cursor por prazo/pedido, worker REQUIRES_NEW com SKIP LOCKED e recuperação após reinício. Falhas isoladas não bloqueiam páginas seguintes; V4 adiciona índice, sem editar V1–V3. Concorrência e rollback validados com PostgreSQL. |
| 40 | Expor somente as operações necessárias pelo Gateway | APIs aprovadas | Concluída: cinco caminhos/verbos administrativos com ADMIN, URL configurável, timeouts dedicados e transporte preservado. Reservas internas sem rota e acesso negado mesmo a ADMIN. Suíte do Gateway: 78 aprovados, sem falhas ou ignorados; sem mudança no domínio de Inventory. |
| 41 | Acrescentar observabilidade mínima | APIs e reservas estáveis | Concluída: correlação HTTP local, logs seguros por status e resumo de expiração com resultados/falhas. Health/métricas sem exposição nesta entrega; sem novas dependências. Testes e limitação de PostgreSQL em TESTING.md; detalhes em OBSERVABILITY.md. |

Não concentrar todos os testes na tarefa 41: cada entrega deve incluir testes
focados e os testes PostgreSQL pertinentes. Ajustar a numeração/escopo se uma
decisão tornar uma tarefa desnecessária; não implementar funcionalidades só
para preencher a tabela.

## Critérios para concluir a primeira entrega

- Testes unitários, de API/segurança, contratos e persistência pertinentes aprovados.
- Concorrência demonstrada com PostgreSQL: disputa pela última unidade,
  sobreposição de produtos em reservas, ordem consistente de bloqueios e rollback.
- Repetições e falhas não duplicam reposições, consumo ou devoluções de reserva.
- Política de expiração/reconciliação definida antes de uso real de reservas.
- Documentação de arranque, contratos, migrations e limitações atualizada.
- Nenhum checkout passa a prometer estoque reservado enquanto não existir integração.

## Etapa posterior: ligação ao checkout

Definição iniciada na microtarefa 49, em
[contrato Order–Inventory](../contracts/order-inventory-proposal.md), ainda
**rascunho pendente de aprovação**. A conclusão do Cart já foi implementada
nas microtarefas 42–48. O novo documento propõe a ordem pedido/Cart/reserva,
limite de checkout, aborto durável e entregas 50–55, sem alterar o Inventory.

Antes de alterar Order/Cart, definir transporte e identidade entre serviços,
ordem de persistência/reserva, timeouts, respostas ambíguas e compensação após
falha, preservando o protocolo já implementado de conclusão do carrinho.
Não manter transação de banco aberta durante HTTP nem usar transação entre
bancos. Se forem escolhidos eventos, planejar explicitamente broker, Outbox,
deduplicação, contratos e recuperação; não prometer entrega exatamente uma vez.

Múltiplos armazéns, lotes, validade de mercadorias, importação em massa,
reposição automática e ligação a Payment ficam fora da proposta inicial.
