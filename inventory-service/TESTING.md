# Testes do Inventory

O módulo mantém Java 25, Spring Boot 4.1.1 e o wrapper Gradle existente.
Foi acrescentado suporte Flyway pelo starter Spring Boot e pelo módulo
PostgreSQL, sem fixar versões fora da gestão de dependências do projeto.
O Hibernate usa `ddl-auto=validate`; ele não cria tabelas automaticamente.

## Executar em desenvolvimento

Com PostgreSQL e Keycloak locais disponíveis, execute no diretório
`inventory-service`:

```powershell
.\gradlew.bat bootRun --args='--spring.profiles.active=dev'
```

O perfil dev usa os seguintes valores de desenvolvimento, substituíveis por
variáveis de ambiente (ou pelas propriedades externas padrão do Spring Boot):

| Variável | Valor local padrão |
|---|---|
| SHOPSPHERE_INVENTORY_PORT | 8084 |
| SHOPSPHERE_INVENTORY_DB_URL | jdbc:postgresql://localhost:5432/inventory_db |
| SHOPSPHERE_INVENTORY_DB_USER | postgres |
| SHOPSPHERE_INVENTORY_DB_PASSWORD | postgres, somente desenvolvimento local |
| SHOPSPHERE_JWT_ISSUER | http://localhost:8180/realms/shopsphere |
| SHOPSPHERE_JWT_AUDIENCE | shopsphere-api |

`init.sql` já prevê inventory_db, mas o entrypoint do PostgreSQL executa esse
arquivo somente na inicialização de um volume novo. Esta entrega não cria
banco, não remove volumes e não aplica migrations em ambiente remoto.

A audiência `shopsphere-api` deve estar presente nos tokens para Inventory.
Na microtarefa 32, o cliente de serviço e seu mapper foram provisionados no
Keycloak local, e a política ADMIN/INVENTORY_SERVICE passou a ser implementada
por SecurityFilterChain próprio. O cadastro foi acrescentado na microtarefa 33;
as demais operações de negócio continuam nas entregas seguintes.
Configuração e testes estão em [SECURITY.md](SECURITY.md).

Flyway está habilitado por padrão, usando o mesmo datasource da aplicação e
o caminho padrão `classpath:db/migration`. A V1 acrescentada na microtarefa 31
cria `inventory_stock`; a V2 da microtarefa 33 acrescenta `inventory_movements`
para cadastro idempotente. Não há migrations vazias nem tabelas fictícias.

Fora de dev, fornecer datasource e configuração JWT do ambiente. O perfil test
é exclusivo dos testes: desativa datasource/Flyway e não deve iniciar um
ambiente real da aplicação.

## Testes sem infraestrutura externa

```powershell
.\gradlew.bat test --tests '*InventoryFlywayConfigurationTests' --tests '*InventoryServiceApplicationTests' --offline --no-daemon
```

`InventoryServiceApplicationTests` verifica que o contexto test não possui
DataSource, Flyway ou EntityManagerFactory. Decoder JWT, repositórios e Catalog são simulados.
`InventoryFlywayConfigurationTests` usa datasource/estratégia de migration
simulados para confirmar a inicialização automática de Flyway, sua desativação
em test e o carregamento/override das propriedades dev sem conectar a serviços.
Esses testes não validam assinatura, issuer ou audiência de um token real.

## Integração opt-in com PostgreSQL

`InventoryStartupPersistenceTests` exige a variável de URL abaixo. Sem ela, o
teste é ignorado. Ele fornece seu próprio datasource e inicia o contexto da
aplicação com Flyway/JPA reais, usando um schema exclusivo
`inventory_startup_test_<uuid>`. O decoder JWT é simulado; não usa Keycloak.

```powershell
$env:SHOPSPHERE_TEST_POSTGRES_URL = 'jdbc:postgresql://localhost:5432/inventory_db'
# Se necessário, configurar também SHOPSPHERE_TEST_POSTGRES_USER e
# SHOPSPHERE_TEST_POSTGRES_PASSWORD; os padrões de teste locais são postgres.
.\gradlew.bat test --offline --no-daemon --rerun-tasks
```

O usuário de teste precisa poder criar/remover schemas na base local indicada.
Os testes verificam a aplicação da V1, duas inicializações com preservação de
dados e sem reaplicar migrations V1/V2, Hibernate em validate e as tabelas esperadas.
Cada caso remove apenas o schema gerado pelo próprio teste; não executa clean
no banco configurado.
O teste não inicia servidor HTTP e não comprova o arranque com Keycloak real.
O registro técnico de criação do schema no histórico Flyway é permitido;
ele não representa uma migration versionada de domínio.

Não executar esta integração contra banco remoto/compartilhado sem autorização
específica. O teste mantém os schemas isolados e não altera o schema de domínio
usado pela aplicação fora dos testes.

## Validação da microtarefa 30

Os quatro testes focados sem infraestrutura passaram. A suíte completa com
PostgreSQL local passou nos cinco testes, sem falhas ou ignorados, usando o
comando opt-in acima. A primeira execução do teste PostgreSQL tinha uma
expectativa incorreta de histórico vazio: Flyway registra a criação do schema.
Essa expectativa foi corrigida para distinguir esse registro técnico de uma
migration versionada e exigir somente a tabela de histórico no schema.
Nenhum ajuste na configuração de produção foi necessário para resolver isso.

## Segurança e erros — microtarefa 32

Os testes sem Keycloak real usam RSA/JWKS locais e controllers auxiliares,
sem PostgreSQL. O teste opt-in de token real requer
SHOPSPHERE_TEST_INVENTORY_SERVICE_TOKEN e o Keycloak disponível. A integração
PostgreSQL continua opt-in pela variável de URL, sem depender desse token.
Consultar [SECURITY.md](SECURITY.md) para a matriz, comandos e provisionamento.

Validação da microtarefa 32: suíte completa aprovada com 73 testes, sem falhas
ou ignorados, ativando PostgreSQL e token real do Keycloak local. O secret e o
token usados na execução não foram gravados no repositório. O runner de
persistência exclui TestComponent explicitamente para manter os controllers
auxiliares fora do contexto, sem desativar validação JPA ou migrations.

## Posição de estoque — microtarefa 31

`InventoryStock` mapeia `inventory_stock` com productId UUID atribuído pelo
cadastro, sem geração de outro ID e sem FK para Catalog. O construtor aceita
onHand entre 0 e Integer.MAX_VALUE, exige produto e inicia reserved em zero.
Disponível é calculado por `onHand - reserved`, sem coluna própria e sem setters
públicos para modificar os contadores.

`InventoryStockRepository` usa o mesmo padrão JpaRepository dos outros módulos.
A versão usa `Long` inicialmente nulo, para o Spring Data reconhecer um novo
objeto com ID atribuído e executar INSERT. Hibernate inicializa a versão em
zero; a coluna é BIGINT NOT NULL. Assim, um novo cadastro com produto existente
não é tratado como merge para sobrescrever saldo. Escritas com versão antiga
são rejeitadas pelo optimistic locking. Isso não substitui o protocolo de
bloqueios para ajustes/reservas, que será implementado nas próximas entregas.

`V1__create_inventory_stock.sql` cria:

- product_id UUID como PK `pk_inventory_stock`.
- on_hand e reserved INTEGER NOT NULL, com padrão zero.
- version BIGINT NOT NULL, com padrão zero, e timestamps TIMESTAMPTZ NOT NULL.
- `chk_inventory_stock_on_hand`: on_hand >= 0.
- `chk_inventory_stock_reserved`: reserved >= 0.
- `chk_inventory_stock_reserved_on_hand`: reserved <= on_hand.

O tipo INTEGER limita os contadores a 2147483647. Não há índices redundantes,
tabelas de movimentos/reservas ou operações HTTP nesta entrega. A V1 será
executada pelo Flyway no próximo arranque da aplicação com seu datasource;
nesta validação foi aplicada somente em schemas temporários de teste.

`InventoryStockTests` cobre limites, produto obrigatório e rejeição de saldo
negativo. `InventoryStartupPersistenceTests` cobre mapeamento JPA, reinício,
saldo reservado/carregamento do disponível, unicidade por produto, checks e
overflow via SQL direto, além da rejeição de uma escrita com versão antiga.
No teste dessa escrita, a mutação é simulada somente no fixture; não foi criado
um setter público ou endpoint de ajuste antecipadamente.

```powershell
.\gradlew.bat test --tests '*InventoryStockTests' --tests '*InventoryFlywayConfigurationTests' --tests '*InventoryServiceApplicationTests' --offline --no-daemon
```

Validação da microtarefa 31: os nove testes focados passaram. A suíte completa
com PostgreSQL local, com o comando opt-in acima, aprovou 18 testes, sem falhas
ou ignorados. Não foi executada migration no schema usado pela aplicação fora
dos testes, nem foi validado Keycloak real nesta entrega.

## Cadastro de estoque — microtarefa 33

Contrato implementado, fluxo HTTP/transacional, V2 e testes estão em
[REGISTRATION.md](REGISTRATION.md). As consultas GET ficam para a microtarefa 35.
Os testes de cadastro usam Catalog simulado/servidor HTTP de teste; os casos
PostgreSQL são ativados por SHOPSPHERE_TEST_POSTGRES_URL, como os demais.

Validação da microtarefa 33: testes focados HTTP/adapter/caso de uso e de
persistência aprovados. A suíte completa (`.\gradlew.bat test --offline
--no-daemon --rerun-tasks`) aprovou 120 testes, sem falhas ou ignorados, com
PostgreSQL e token real do Keycloak ativados. O token verifica a segurança de
serviço; o cadastro usa JWT ADMIN simulado nos testes. Catalog é simulado ou
servidor HTTP de teste, sem cadastro na base de uso normal.

## Ajustes de estoque — microtarefa 34

Contrato, concorrência e arquivos estão em [ADJUSTMENTS.md](ADJUSTMENTS.md).
Executados testes focados de HTTP/domínio/persistência e, em seguida, a suíte
completa: `.\gradlew.bat test --offline --no-daemon --rerun-tasks`, com
SHOPSPHERE_TEST_POSTGRES_URL apontando para inventory_db local.

Resultado: 135 testes contabilizados, 134 aprovados, zero falhas/erros e um
ignorado: teste opcional de token real, sem
SHOPSPHERE_TEST_INVENTORY_SERVICE_TOKEN nesta execução. PostgreSQL habilitado;
schemas temporários próprios. Não houve alteração no Keycloak ou validação
com Catalog real nesta entrega. Segurança HTTP validada com JWTs de teste.

## Consultas de estoque — microtarefa 35

Detalhes e arquivos em [QUERIES.md](QUERIES.md). Executados:

- `.\gradlew.bat test --tests '*InventoryQueryHttpTests' --offline --no-daemon`
- Com SHOPSPHERE_TEST_POSTGRES_URL apontando para inventory_db local:
  `.\gradlew.bat test --offline --no-daemon --rerun-tasks --tests '*InventoryQuery*Tests'`
- Com o mesmo PostgreSQL: `.\gradlew.bat test --offline --no-daemon --rerun-tasks`

Os 12 testes focados passaram. Suíte completa: 147 contabilizados, 146
aprovados, zero falhas/erros e um ignorado: teste opcional de token real do
Keycloak, sem SHOPSPHERE_TEST_INVENTORY_SERVICE_TOKEN nesta execução.
Os testes PostgreSQL usam schemas temporários próprios; nenhum dado de uso
normal foi alterado. Segurança HTTP validada com JWTs de teste.

## Persistência de reservas — microtarefa 36

Modelo, migration e arquivos em
[RESERVATION_PERSISTENCE.md](RESERVATION_PERSISTENCE.md). Executados:

- `.\gradlew.bat test --tests '*InventoryReservationTests' --offline --no-daemon`
- Com SHOPSPHERE_TEST_POSTGRES_URL apontando para inventory_db local:
  `.\gradlew.bat test --offline --no-daemon --rerun-tasks --tests '*InventoryReservation*Tests' --tests '*InventoryStartupPersistenceTests'`
- Com o mesmo PostgreSQL: `.\gradlew.bat test --offline --no-daemon --rerun-tasks`

Os 30 testes focados passaram: 11 do modelo, nove da persistência de reservas
e dez de arranque/persistência existente. Suíte completa: 167 contabilizados,
166 aprovados, zero falhas/erros e um ignorado: teste opcional de token real
do Keycloak, sem SHOPSPHERE_TEST_INVENTORY_SERVICE_TOKEN nesta execução.

V3 aplicada somente em schemas temporários próprios. Validados Hibernate
validate, upgrade V2→V3, reinício, estados, cliente proprietário, versão,
rollback e constraints SQL. Nenhuma migration aplicada ao schema de uso normal.
Não há endpoints de reserva nem testes de efeitos de reserva sobre o saldo
nesta etapa; esses fluxos pertencem às microtarefas 37–39.

## Criação e consulta de reservas — microtarefa 37

Fluxo transacional, contratos e arquivos em [RESERVATIONS.md](RESERVATIONS.md).
Executados:

- `.\gradlew.bat test --tests '*InventoryReservationTests' --tests '*InventoryServiceApplicationTests' --offline --no-daemon`
- `.\gradlew.bat test --tests '*InventoryReservationHttpTests' --tests '*ReserveStockUseCaseTests' --tests '*InventoryStockTests' --offline --no-daemon`
- Com SHOPSPHERE_TEST_POSTGRES_URL apontando para inventory_db local:
  `.\gradlew.bat test --offline --no-daemon --rerun-tasks --tests '*InventoryReservationCreationTests'`
- Com o mesmo PostgreSQL: `.\gradlew.bat test --offline --no-daemon --rerun-tasks`

A primeira execução PostgreSQL revelou falta de conversão de String para
Duration no ApplicationContextRunner. Corrigida a produção para leitura
explícita ISO-8601, os dez casos PostgreSQL passaram. O caso adicional de
100 itens foi então validado na suíte completa. Resultado final: 195 testes
contabilizados, 194 aprovados, zero falhas/erros e um ignorado: teste opcional
de token real do Keycloak, sem SHOPSPHERE_TEST_INVENTORY_SERVICE_TOKEN.

Inclui 14 testes HTTP de reservas, dois de validação/configuração e 11 de
criação com PostgreSQL, além dos testes de domínio e regressões existentes.
Validados replay reordenado e terminal/vencido, TTL configurável, cliente
proprietário, rollback/reuso de orderId, 100 itens, última unidade,
mesmo pedido/intenção divergente e reservas sobrepostas, inclusive disputa
com ajuste. Os cenários de terminal/vencido usam fixtures, sem implementação
de confirmação/liberação/expiração nesta etapa.

Banco restrito aos schemas temporários próprios. Nenhuma migration nova ou
alteração de dados de uso normal. HTTP usa JWTs de teste e segurança real;
Catalog simulado não é chamado pelo fluxo de reserva.

## Confirmar e liberar reservas — microtarefa 38

Contratos, matriz e arquivos em
[RESERVATION_TRANSITIONS.md](RESERVATION_TRANSITIONS.md). Executados:

- `.\gradlew.bat test --tests '*InventoryReservation*HttpTests' --tests '*InventoryReservationTests' --tests '*InventoryStockTests' --offline --no-daemon`
- Com SHOPSPHERE_TEST_POSTGRES_URL apontando para inventory_db local:
  `.\gradlew.bat test --offline --no-daemon --rerun-tasks --tests '*InventoryReservationTransitionTests'`
- Com o mesmo PostgreSQL: `.\gradlew.bat test --offline --no-daemon --rerun-tasks`

Testes focados aprovados, incluindo 15 casos PostgreSQL de transições. Suíte
completa: 232 contabilizados, 231 aprovados, zero falhas/erros e um ignorado:
acceptsProvisionedLocalKeycloakServiceToken, sem
SHOPSPHERE_TEST_INVENTORY_SERVICE_TOKEN nesta execução.

Validados estados e limite exato do prazo; permissões e identidade JWT;
consumo/devolução únicos; preservação de outras reservas e snapshots;
rollback em item posterior; prazo vencido durante espera por lock;
confirmações/liberações concorrentes e disputa entre ambas; reservas
sobrepostas; nova reserva concorrente com liberação; 100 itens.

Sem nova migration. PostgreSQL restrito a schemas temporários próprios,
incluindo fixtures de prazo/contador inconsistente para comprovar rollback.
Nenhum dado de uso normal ou configuração de Keycloak foi alterado. O job
automático de expiração/reconciliação permanece para a microtarefa 39.

## Expiração e reconciliação — microtarefa 39

Fluxo, configurações, V4 e arquivos em [EXPIRATION.md](EXPIRATION.md).
Executados:

- `.\gradlew.bat test --tests '*ReservationExpiration*Tests' --tests '*InventoryReservationTests' --offline --no-daemon`
- Com SHOPSPHERE_TEST_POSTGRES_URL apontando para inventory_db local:
  `.\gradlew.bat test --offline --no-daemon --rerun-tasks --tests '*ReservationExpiration*Tests' --tests '*InventoryReservationExpirationTests' --tests '*InventoryReservationTests'`
- Com o mesmo PostgreSQL: `.\gradlew.bat test --offline --no-daemon --rerun-tasks`

As execuções iniciais identificaram erros de matcher Mockito/tipagem nos
testes e uma falha real na seleção inicial de candidatos: PostgreSQL não
inferia o tipo do cursor nulo. Os testes foram corrigidos e as consultas
iniciais/de continuação separadas, eliminando o parâmetro nulo.

Resultado final dos focados: 43 aprovados, incluindo sete testes PostgreSQL
de expiração e os testes de agendamento/configuração/modelo. Suíte completa:
249 contabilizados, 248 aprovados, zero falhas/erros e um ignorado:
acceptsProvisionedLocalKeycloakServiceToken, sem
SHOPSPHERE_TEST_INVENTORY_SERVICE_TOKEN nesta execução.

Validados recuperação após reinício, cursor com prazos iguais e lote de um,
falhas sem bloquear páginas seguintes, rollback integral, tentativa em nova
varredura, SKIP LOCKED, workers concorrentes e disputa com liberação/confirmar.
Agendamento real testado num contexto web dedicado; desativação testada
separadamente. Contextos de persistência não web invocam o job diretamente.

Upgrade V3→V4 validou o índice e preservou estoque/reserva até o processamento
explícito pelo job. V4 e fixtures foram aplicadas somente em schemas temporários
próprios. Nenhum dado de uso normal, configuração de Keycloak ou outro serviço
foi alterado.

## Observabilidade mínima — microtarefa 41

Detalhes, arquivos e limites em [OBSERVABILITY.md](OBSERVABILITY.md).
Executados no módulo Inventory:

- `.\gradlew.bat test --offline --no-daemon --tests '*RequestCorrelationFilterTests' --tests '*ReservationExpirationJobTests' --tests '*ApiExceptionHandlerTests'`
- `.\gradlew.bat test --offline --no-daemon --tests '*RequestCorrelationFilterTests' --tests '*ReservationExpirationJobTests' --tests '*ApiExceptionHandlerTests' --tests '*InventoryJwtSecurityTests'`
- `.\gradlew.bat test --offline --no-daemon`

Todos os comandos terminaram com BUILD SUCCESSFUL. O conjunto focado ampliado
contabilizou 61 testes: 60 aprovados e um teste opcional de token real ignorado.
A suíte completa contabilizou 243 testes: 184 aprovados, zero falhas/erros e
59 ignorados pelas condições existentes de ambiente. PostgreSQL não estava
acessível em localhost:5432 e SHOPSPHERE_TEST_POSTGRES_URL não foi configurada;
os testes condicionados ao banco não foram revalidados. Também não foi fornecido
o token opcional de Keycloak. Nenhuma migration foi executada nesta entrega.

Validados correlação em 401/403 e sucesso com a cadeia real de segurança,
status 409/502/503, UUID local ignorando header recebido, ausência de dados
sensíveis nos eventos, restauração do MDC após falha e preservação de outras
entradas. O job foi validado para contagem de expirados/ignorados/falhas,
continuação após falha individual e resumo de consulta interrompida sem
suprimir sua exceção. `git diff --check` sem erros de whitespace.

## Reexecução das integrações ignoradas — 2026-10-26

Com PostgreSQL e Keycloak locais disponíveis, foram executados somente os
conjuntos anteriormente condicionados à infraestrutura:

- PostgreSQL: SHOPSPHERE_TEST_POSTGRES_URL=jdbc:postgresql://localhost:5432/inventory_db;
  `.\gradlew.bat test --offline --no-daemon --rerun-tasks` com oito seletores
  `--tests` de classe completa no package br.com.hadryan.shosphere.inventory.persistence:
  InventoryAdjustmentPersistenceTests, InventoryQueryPersistenceTests,
  InventoryRegistrationPersistenceTests, InventoryReservationCreationTests,
  InventoryReservationTransitionTests, InventoryReservationExpirationTests,
  InventoryReservationPersistenceTests e InventoryStartupPersistenceTests.
  Resultado XML: 68 testes aprovados, zero falhas/erros e zero ignorados.
- Keycloak: `.\gradlew.bat test --offline --no-daemon --tests '*InventoryJwtSecurityTests.acceptsProvisionedLocalKeycloakServiceToken'`.
  Token de client_credentials obtido do cliente local shopsphere-order existente,
  mantido somente em memória e na variável do processo de teste; variável removida
  ao terminar. Resultado XML: um teste aprovado, zero falhas/erros e zero ignorados.

Total desta reexecução: 69 testes aprovados. Os 59 ignorados da execução anterior
incluíam casos parametrizados que, habilitados, geram mais casos executáveis.
Não foi repetida a suíte inteira nesta revalidação.

A primeira tentativa de banco parou por falta de acesso à cache Gradle fora do
workspace; foi repetida com autorização. O filtro inicial '*persistence.*' foi
expandido para um nome de arquivo e não encontrou testes; a execução final usou
os oito nomes completos acima. Não foi necessário alterar código ou testes.
As migrations foram exercitadas apenas nos schemas temporários dos testes.
Não houve alteração de configuração do Keycloak nem de dados de uso normal.
