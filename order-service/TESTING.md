# Flyway no serviço de pedidos

## Consulta de pedido — microtarefa 25

`GET /api/v1/orders/{id}` consulta o recurso indicado pelo Location do POST.
Exige autenticação e utiliza o UUID `sub` do JWT como cliente. O ID do cliente
não é recebido do body nem de parâmetros de consulta. A consulta filtra por
`id` e `customerId` no repositório, com itens carregados por `EntityGraph`, e
constrói o `OrderResponse` numa transação somente de leitura.

O proprietário recebe `200` com o mesmo formato de resposta do checkout e os
snapshots persistidos. Não há chamadas a Cart/Catalog nem alteração do pedido.
Pedido inexistente ou de outro cliente retorna `404`, com `Order not found`
em `application/problem+json`, seguindo o padrão de propriedade do Cart.
ADMIN também fica limitado aos próprios pedidos. Acesso anónimo retorna `401`
e UUID inválido no caminho retorna `400`. O campo `paymentStatus` continua
nulo, conforme o mapper existente; não foi acrescentada integração de pagamento.

`OrderControllerTests` verifica identidade, autenticação e ID inválido.
`OrderServiceApplicationTests` exercita o caso de uso real com repositório
simulado, incluindo snapshots e isolamento de cliente/ADMIN, sem chamadas
externas ou escritas. Execute no diretório `order-service`:

```powershell
.\gradlew.bat test --tests '*OrderControllerTests' --tests '*OrderServiceApplicationTests' --offline --no-daemon
```

`OrderCreationPersistenceTests` verifica também a consulta após commit, os
itens e o isolamento por proprietário com PostgreSQL real. Para ativar essa
validação, use as variáveis de banco descritas neste documento. Sem a variável
de URL, a integração é ignorada. Não há novas migrations, alterações no POST,
no payload existente ou nas configurações de segurança/Gateway.

Validação desta entrega: testes HTTP focados e suíte completa sem banco
aprovados; com `SHOPSPHERE_TEST_POSTGRES_URL` apontando para `order_db` local,
`.\gradlew.bat test --offline --no-daemon --rerun-tasks` aprovou os 131 testes,
sem falhas ou ignorados, incluindo migrations, persistência e concorrência.

## Erros padronizados — microtarefa 22

O contrato está em `../contracts/api-errors.md`. Os códigos explícitos do
checkout são preservados e o body usa `application/problem+json`. Conflitos
de persistência chegam a `409` sem expor SQL; a concorrência de idempotência
continua resolvida no caso de uso, com replay `201`. Erros inesperados recebem
`500` com mensagem genérica. Os testes de aplicação verificam que uma falha
de gravação não produz confirmação de criação.

## Entrada HTTP de checkout — microtarefa 17

`POST /api/v1/orders` extrai o cliente do `sub` do JWT e repassa a chave UUID
do header obrigatório `Idempotency-Key` ao `CreateOrderUseCase`. Identidade,
itens e preços não são obtidos do body. A criação bem-sucedida usa o DTO
`OrderResponse`, retorno `201` e `Location: /api/v1/orders/{id}`.

O controller rejeita um retorno nulo ou sem ID com `500`, sem `Location`, em
vez de confirmar uma criação inexistente. Desde a tarefa 19, o caso de uso real
persiste o pedido validado e só entrega o resultado após o commit da transação.
A garantia de idempotência está descrita na tarefa 20 abaixo.

`OrderControllerTests` verifica delegação, contrato de sucesso com caso de uso
simulado, autenticação, header inválido/ausente e falhas sem confirmação falsa.
`OrderServiceApplicationTests` também exercita controller e caso de uso reais,
com adapters externos e repositório simulados, verificando gravação e falhas.
Na tarefa 17 não foram adicionadas migrações, endpoint de consulta ou regras
de idempotência; a consulta foi acrescentada na microtarefa 25 acima.

## Validações de checkout — microtarefa 18

O corpo de `POST /api/v1/orders` agora é obrigatório e deve conter o endereço:

```json
{
  "shippingAddress": {
    "recipient": "Customer",
    "addressLine": "Street 1",
    "addressLine2": null,
    "city": "Lisbon",
    "postalCode": "1000-001",
    "countryCode": "PT"
  }
}
```

Os limites seguem as colunas existentes: destinatário 150, linhas 200, cidade
100 e código postal 20 caracteres. `addressLine2` é opcional; os demais campos
são obrigatórios e não podem ser vazios. O país usa duas letras maiúsculas.
Não há consulta de validade postal ou disponibilidade de entrega nesta etapa.
Consumidores que enviavam `{}` ou nenhum corpo precisam enviar o endereço.

O campo `items` já existente no DTO é ignorado pelo checkout: produto e
quantidade vêm do carrinho do usuário, e nome, preço e moeda vêm do catálogo.
O DTO de resposta interna de Cart passou a ler também `status`, que já é
fornecido pelo serviço de carrinho; não houve mudança na API do produtor.

As validações impedem prosseguir com carrinho vazio/inativo, itens sem ID,
quantidades não positivas ou produtos duplicados. O catálogo deve devolver
todos os IDs solicitados, sem duplicação nem produtos extras. Produtos devem
estar ativos, ter nome não vazio de até 150 caracteres, preço positivo e moeda
ISO reconhecida, igual para todos os itens. A ordem da resposta não importa.

Respostas de erro nesta etapa:

- `400`: endereço ausente/inválido; também validado no caso de uso antes de HTTP.
- `404`: Cart informa que o carrinho ativo não existe.
- `409`: carrinho incompatível com checkout, produto ausente/inativo, preço
  inválido ou moedas diferentes.
- `502`: resposta malformada de serviço, produtos extras/duplicados, nome/moeda
  inválidos ou falha HTTP não tratada especificamente.
- `503`: falha de conexão/leitura representada por `RetryableException`.

Feign usa conexão de 2 segundos e leitura de 5 segundos para Cart e Catalog.
Não foram adicionados retries; permanece a política padrão sem retry do
Spring Cloud OpenFeign. As chamadas continuam fora de transações de banco.

`CheckoutValidationTests` cobre as regras, propagação do Bearer token, falhas
de integração e ausência de gravações em entradas inválidas. Os testes de
controller verificam validação aninhada do endereço e identidade pelo JWT.
Dados válidos seguem para cálculo e persistência, implementados na tarefa 19.

## Cálculo e persistência — microtarefa 19

O caso de uso relaciona produtos por ID, preservando as quantidades do Cart e
copiando nome, preço e moeda do Catalog. O body não define preço, total ou itens
do pedido. O endereço validado é copiado integralmente, incluindo a segunda
linha opcional. Alterações futuras no catálogo não atualizam esses snapshots.

`Order.addItem` calcula `unitPrice * quantity`, acumula o subtotal e mantém
`totalAmount = subtotal - discountAmount + shippingAmount`. Desconto e frete
permanecem em zero, conforme os valores iniciais do modelo. Todos os valores
usam `BigDecimal` com duas casas, conforme `NUMERIC(19,2)`. Valores que exigem
arredondamento, linhas ou totais acima da capacidade do banco são rejeitados
com `409` antes de abrir a transação; não há arredondamento silencioso.

`CreateOrderTransaction` grava o agregado com `saveAndFlush`, usando cascade
para os itens, e mapeia `OrderResponse` dentro da transação. O proxy Spring
só devolve o resultado depois do commit. Uma falha na gravação desfaz cabeçalho
e itens. Cart e Catalog são consultados antes dessa transação, sem HTTP dentro
dela. O estado inicial continua `PENDING_STOCK`; não significa estoque reservado
ou pagamento confirmado. `paymentStatus` permanece nulo, pois pagamento não
faz parte desta entrega.

A chave é armazenada junto ao pedido. Desde a tarefa 20, a V3 garante sua
unicidade por cliente, e retries reutilizam o pedido. A tarefa 19 não adicionou
migrations, reserva de estoque, pagamento, Outbox,
alteração do carrinho ou protocolo de snapshot/lock distribuído nesta etapa.

`OrderAmountsTests` cobre cálculo decimal, limites e ausência de mutação parcial
do agregado em falhas. `OrderCreationPersistenceTests` usa PostgreSQL real,
Flyway e transações Spring para verificar commit, snapshots, endereço completo,
consultas externas sem transação e rollback quando um item viola uma constraint.
O controller é invocado diretamente nesse teste; os contratos HTTP e a segurança
continuam cobertos pelos testes específicos. Cart/Catalog são simulados.

Com as variáveis de PostgreSQL descritas abaixo, execute:

```powershell
.\gradlew.bat test --tests '*OrderCreationPersistenceTests' --rerun-tasks
```

Os testes usam schemas exclusivos removidos ao final; sem a variável de URL,
os testes de integração são ignorados.

## Idempotência — microtarefa 20

A V3 cria `uk_orders_customer_idempotency`, índice único de
`(customer_id, idempotency_key)`. Pedidos legados com chave nula continuam
permitidos. A chave permanece `VARCHAR(100)` no banco e UUID no header HTTP,
sem reescrever V1/V2 ou modificar o payload de checkout.

Após validar o endereço, o caso de uso busca um pedido desse cliente com a
mesma chave. Se encontrado e com o mesmo endereço, devolve o pedido salvo
sem consultar Cart/Catalog nem gravar de novo. O HTTP continua `201` com o
mesmo ID e `Location`. A resposta usa a representação persistida do pedido;
não há armazenamento ou reprodução byte a byte da resposta HTTP original.

O endereço é comparado campo a campo, sem normalização: destinatário, linhas,
cidade, código postal e país. Uma diferença retorna `409`, sem chamadas
externas. Itens enviados pelo frontend continuam ignorados e não participam
da comparação. Alterações posteriores no carrinho ou catálogo não transformam
um retry em novo checkout: uma nova intenção de compra precisa de nova chave.
Clientes diferentes podem usar a mesma chave sem compartilhar pedidos.

Na concorrência, o índice deixa somente uma criação vencer. A escrita executa
em transação independente. A tentativa perdedora é revertida; somente o
conflito do índice de idempotência é tratado como replay. Depois do rollback,
uma nova transação de leitura recupera o pedido vencedor e verifica o endereço.
Outras violações de integridade continuam falhando, sem serem mascaradas.
Não há retry automático de INSERT. Uma tentativa revertida não reserva a chave.

Antes de aplicar V3 em um banco existente, verifique duplicatas:

```sql
SELECT customer_id, idempotency_key, count(*)
FROM orders
WHERE idempotency_key IS NOT NULL
GROUP BY customer_id, idempotency_key
HAVING count(*) > 1;
```

Se houver duplicatas, a migration falha e exige revisão dos registros; não
apaga dados nem escolhe silenciosamente um pedido. Pedidos antigos que tenham
chave, mas não tenham endereço, retornam conflito caso essa chave seja reutilizada.

`OrderIdempotencyTests` cobre replay, comparação do endereço e distinção dos
erros de integridade. `OrderCreationPersistenceTests` verifica PostgreSQL real:
quatro chamadas simultâneas com uma chave, conflito concorrente de endereços,
isolamento por cliente, nova chave, retry sem serviços externos e retry após
rollback. `OrderSchemaMigrationTests` também verifica que duplicatas anteriores
bloqueiam V3 sem remoção de dados. Os testes HTTP preservam `201`/`Location` no
replay e validam `409` no conflito de endereço.

Com as variáveis de PostgreSQL abaixo, execute:

```powershell
.\gradlew.bat test --tests '*OrderIdempotencyTests' --tests '*OrderCreationPersistenceTests' --rerun-tasks
```

Cart/Catalog continuam simulados nos testes, sem HTTP dentro de transações.
Não foram adicionados bloqueio distribuído de carrinho, estoque, pagamentos ou Outbox.

## Segurança JWT — microtarefa 10

O perfil `dev` configura o emissor de tokens em
`http://localhost:8180/realms/shopsphere`. `SHOPSPHERE_JWT_ISSUER` permite
substituí-lo conforme o ambiente. Nos demais perfis, configure
`spring.security.oauth2.resourceserver.jwt.issuer-uri`, por exemplo pela variável
`SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI`.

O Resource Server cria o decoder pela configuração padrão do Spring Boot e
valida assinatura, validade temporal e emissor. As rotas de pedidos exigem
autenticação; não é necessário ser administrador. Regras de proprietário
continuam obrigatórias nos casos de uso conforme forem implementados.

Conforme o documento de contexto, a audiência só deve ser exigida depois de
confirmar o mapper do Keycloak. No perfil `dev`, defina
`SHOPSPHERE_JWT_AUDIENCE=shopsphere-api` quando os tokens contiverem esse valor
em `aud`. Sem essa variável, a audiência não é obrigatória em pedidos.
Gateway e outras APIs possuem suas próprias configurações de audiência.

`OrderJwtSecurityTests` usa chaves RSA geradas em memória, um servidor JWKS local
temporário e o decoder real do Spring Boot. Testa autenticação, assinatura,
expiração, emissor e audiência configurável, sem depender do Keycloak ou banco.

```powershell
.\gradlew.bat test --tests '*OrderJwtSecurityTests'
```

## Migrações

O serviço inclui o starter Flyway do Spring Boot e o módulo de suporte ao
PostgreSQL. A configuração padrão usa o mesmo datasource do serviço, encontra
scripts em `classpath:db/migration` e executa as migrações na inicialização,
antes dos componentes dependentes do banco, como o Hibernate.

Não é necessário criar um bean Flyway ou chamar `migrate()` no código da
aplicação. O perfil `dev` mantém `ddl-auto: validate`: a criação e a evolução
do esquema pertencem às migrações.

`OrderFlywayConfigurationTests` verifica a configuração e a chamada da
inicialização sem acessar um banco. `OrderFlywayMigrationTests` usa PostgreSQL
real para verificar criação das tabelas e reinicialização sem reaplicar
migrações. Ele usa um schema exclusivo e remove esse schema ao terminar.

Execute no diretório `order-service`, em PowerShell:

```powershell
$env:SHOPSPHERE_TEST_POSTGRES_URL = 'jdbc:postgresql://localhost:5432/order_db'
$env:SHOPSPHERE_TEST_POSTGRES_USER = 'postgres'
$env:SHOPSPHERE_TEST_POSTGRES_PASSWORD = 'postgres'
.\gradlew.bat test --tests '*OrderFlyway*Tests' --rerun-tasks
```

O usuário precisa de permissão para criar e remover schemas. Sem
`SHOPSPHERE_TEST_POSTGRES_URL`, o teste de integração é ignorado.

## Alinhamento do esquema — microtarefa 09

A V2 adiciona os campos financeiros, endereço e chave de idempotência; renomeia
`order_items.total_price` para `line_total`; e aceita todos os estados atuais.
A V1 permanece intacta. A chave usa `VARCHAR(100)`, conforme o modelo existente;
a unicidade por cliente e a execução idempotente foram adicionadas na tarefa 20.
Essa V2 substitui a numeração apenas proposta no documento de contexto:
a idempotência ocupa V3; migrations futuras de outbox deverão usar os próximos números livres.

Pedidos anteriores mantêm os estados `PENDING` e `COMPLETED`, agora também
reconhecidos pelo enum. Novos pedidos continuam em `PENDING_STOCK`; pedidos
legados concluídos não podem ser cancelados. Não há conversão automática dos
estados antigos para etapas de estoque, pagamento ou entrega.

Para dados anteriores, o subtotal recebe o total já registrado e descontos e
frete recebem zero, pois a V1 não distinguia esses componentes. O valor final
é preservado. Endereços e chaves ausentes continuam nulos; não são inventados
dados históricos. O endereço embutido é opcional na persistência; a validação
dos dados exigidos para novos checkouts pertence à tarefa 18.

`OrderSchemaMigrationTests` cria um schema exclusivo, executa V1, insere pedidos
legados e itens, aplica V2 e inicia Hibernate com `ddl-auto=validate`. Verifica
preservação dos dados e persistência dos novos snapshots. Use as mesmas variáveis
de PostgreSQL acima e execute:

```powershell
.\gradlew.bat test --tests '*OrderSchemaMigrationTests' --rerun-tasks
```

Sem PostgreSQL configurado, esse teste é ignorado. Os testes isolados de Flyway
não iniciam JPA, Feign ou segurança JWT.

## Intenção de checkout e origem do pedido — microtarefa 45

Modelo, transações, arquivos e limites em
[CHECKOUT_PERSISTENCE.md](CHECKOUT_PERSISTENCE.md).
CheckoutIntentTests cobre invariantes, cópia do snapshot, endereço exato,
transições terminais e metadados de retomada. CheckoutIntentPersistenceTests usa
Flyway, JPA/Hibernate validate, transações reais e PostgreSQL em schemas temporários.

Executados no módulo Order:

```powershell
.\gradlew.bat test --offline --no-daemon --tests '*OrderIdempotencyTests' --tests '*OrderServiceApplicationTests'
$env:SHOPSPHERE_TEST_POSTGRES_URL = 'jdbc:postgresql://localhost:5432/order_db'
.\gradlew.bat test --offline --no-daemon --tests '*CheckoutIntentTests' --tests '*CheckoutIntentPersistenceTests'
.\gradlew.bat test --offline --no-daemon --tests '*CheckoutIntentTests' --tests '*CheckoutIntentPersistenceTests' --tests '*OrderServiceApplicationTests'
.\gradlew.bat test --offline --no-daemon --rerun-tasks
```

Os testes existentes focados passaram. A primeira compilação dos testes novos
revelou ambiguidade de import entre Order de domínio e a anotação JUnit; os
imports JUnit foram explicitados. A execução seguinte revelou precisão diferente
de updatedAt na resposta inicial e no replay após reinício. A nova transação
passou a reler o pedido gravado antes de construir a resposta; o fluxo público
anterior não foi alterado. O conjunto focado ampliado passou após a correção.

A suíte completa aprovou **154 testes**, zero falhas/erros e zero ignorados.
São seis casos unitários e 17 testes PostgreSQL novos. Cobertura:

- Upgrade V3 com pedido existente para V4, sem fabricar origem ou endereço;
  criação antiga e múltiplas origens nulas continuam válidas.
- Endereço/chave e snapshot preservados após reinício; null versus vazio
  em addressLine2; isolamento por cliente e rejeição de snapshot substituto.
- Criação concorrente da intenção por cliente/chave, endereços incompatíveis,
  duas gravações para a mesma intenção e duas intenções para o mesmo carrinho.
- Commit conjunto de pedido/itens/origem e COMPLETION_PENDING, replay de
  snapshots financeiros e metadados, e rollback quando a atualização da intenção falha.
- Aborto versus criação do pedido, chave consumida após aborto, resultado de
  falha obsoleto sem regressão de estado, constraints e versão otimista.

V4 foi exercitada apenas em schemas temporários de teste, removidos pelo
procedimento de limpeza do teste. V1–V3 não foram modificadas. Não houve
execução em banco compartilhado, mudança de segurança/contratos públicos,
chamada ao protocolo interno do Cart, aquisição de token ou worker agendado.

## Orquestração por snapshot — microtarefa 46

Comportamento, configuração temporária de desenvolvimento, arquivos e limites em
[CHECKOUT_ORCHESTRATION.md](CHECKOUT_ORCHESTRATION.md).

Executados no módulo Order:

```powershell
.\gradlew.bat test --offline --no-daemon --tests '*OrderServiceApplicationTests' --tests '*CheckoutIntentTests'
.\gradlew.bat test --offline --no-daemon --tests '*CartCheckoutClientTests'
$env:SHOPSPHERE_TEST_POSTGRES_URL = 'jdbc:postgresql://localhost:5432/order_db'
.\gradlew.bat test --offline --no-daemon --tests '*SnapshotCheckoutPersistenceTests'
.\gradlew.bat test --offline --no-daemon --tests '*SnapshotCheckoutPersistenceTests' --tests '*CartCheckoutClientTests' --tests '*CheckoutModeTests'
.\gradlew.bat test --offline --no-daemon --tests '*CheckoutModeTests'
.\gradlew.bat test --offline --no-daemon --rerun-tasks
```

Os testes existentes e os testes dos clientes passaram. A primeira execução da
orquestração revelou uma substituição de stub Mockito que executava a resposta
anterior com argumentos nulos; foi corrigida usando doThrow/doAnswer. No conjunto
ampliado, o teste de seleção de modo tentou injetar persistência num mock criado
como bean comum; passou a registrar esse mock como singleton pré-construído.
Nenhuma validação de produção ou expectativa funcional foi removida.

Repetição dos testes de modo aprovada. A suíte completa com PostgreSQL aprovou
**196 testes**, zero falhas/erros e zero ignorados: 28 casos novos de orquestração
com banco, 12 de cliente HTTP e dois de seleção de modo. Modo novo habilitado
somente nos contextos de teste pertinentes; o padrão da aplicação continua false.

Cobertura inclui:

- POST 201/Location, replay, GET por proprietário e 404 de outro cliente; itens
  do body ignorados em favor do snapshot; valores revalidados no Catalog.
- client_credentials, cache/renovação/invalidação de token, métodos/payloads
  internos, timeouts 2000/5000 ms, timeout de leitura real com servidor HTTP local,
  erros sanitizados e ausência de retry na aplicação.
- Preparação perdida, falha após preparação remota e antes do commit local,
  timeout do Catalog com snapshot preservado, conclusão perdida e retomada após
  reinício sem JWT do utilizador. As chamadas simuladas verificam ausência de
  transação e que o pedido já está commitado antes de complete.
- Aborto registrado antes de HTTP, aborto pendente retomado no replay,
  payloads inválidos preservando trabalho recuperável e valores monetários rejeitados.
- Concorrência na mesma chave e duas chaves para o mesmo carrinho; um pedido
  por origem mesmo quando o Cart simulado permite ambas as preparações.

PostgreSQL/Flyway/JPA usam apenas schemas temporários. Não foram exercitadas
chamadas ao Keycloak ou aos processos reais de Cart/Catalog nesta etapa;
o transporte é testado com servidores HTTP locais e os colaboradores externos
da orquestração são simulados. A etapa 48 cobre a integração entre serviços.

## Recuperação automática — microtarefa 47

Configuração, coordenação e operação em [CHECKOUT_RECOVERY.md](CHECKOUT_RECOVERY.md).
Foram acrescentados CheckoutRecoveryJobTests, testes de ativação em
CheckoutModeTests e nove casos PostgreSQL em SnapshotCheckoutPersistenceTests.

Comandos executados no módulo Order:

```powershell
.\gradlew.bat test --offline --no-daemon --tests '*CheckoutRecoveryJobTests' --tests '*CheckoutModeTests'
$env:SHOPSPHERE_TEST_POSTGRES_URL = 'jdbc:postgresql://localhost:5432/order_db'
.\gradlew.bat test --offline --no-daemon --tests '*SnapshotCheckoutPersistenceTests'
.\gradlew.bat test --offline --no-daemon --tests '*SnapshotCheckoutPersistenceTests' --tests '*CheckoutRecoveryJobTests' --tests '*CheckoutModeTests'
.\gradlew.bat test --offline --no-daemon --rerun-tasks
```

A primeira execução com banco revelou ausência de conversão String/Duration
nos contextos Spring simples dos testes. A leitura dos parâmetros passou a
usar Duration.parse explicitamente, sem alterar a infraestrutura dos testes.
Os testes focados passaram após a correção. A suíte completa com PostgreSQL
aprovou **210 testes**, zero falhas/erros e zero ignorados, incluindo os dois
casos de bloqueio/falha local acrescentados após a execução focada.
Uma tentativa de execução também
foi impedida pelo sandbox ao acessar o cache Gradle; foi repetida com permissão.

Cobertura acrescentada:

- Reinício em PREPARING, PREPARED, COMPLETION_PENDING e ABORT_PENDING; job
  completa/aborta sem JWT do utilizador e não seleciona estados terminais.
- Claim concorrente único, agenda do claim preservada após reinício e nova
  elegibilidade após o prazo; SKIP LOCKED na seleção e claim sob lock real.
- Lotes limitados, falha de uma intenção permitindo progresso das seguintes,
  agendas futuras ignoradas, backoff exponencial/teto e contador sem duplicação.
- Falha no banco após preparação remota registra backoff; restauração permite
  retomar o mesmo checkoutId e persistir um único pedido.
- Erros de seleção/claim/bookkeeping isolados, avisos após 15 minutos sem
  expor texto de exceção/stack trace e seleção condicional do job.

PostgreSQL/Flyway/JPA usam schemas temporários. Cart/Catalog permanecem
simulados nesta validação; transporte e token continuam cobertos pelos testes
de cliente existentes, sem chamar Keycloak real ou processos Cart/Catalog.
A etapa 48 valida o fluxo integrado. Não houve migration nova ou ativação do
modo snapshot no ambiente normal.

## Fluxo entre processos — microtarefa 48

Suíte opt-in CheckoutFlowIntegrationTests e infraestrutura de teste
CheckoutTestRuntime, sob src/test/java/br/com/hadryan/shosphere/order/integration.
Procedimento reproduzível, operação, correção encontrada e limites em
[CHECKOUT_OPERATION.md](CHECKOUT_OPERATION.md).

Foram gerados os quatro JARs com `.\gradlew.bat bootJar --offline --no-daemon`
nos módulos Catalog, Cart, Order e Gateway. Com PostgreSQL local e
SHOPSPHERE_TEST_CHECKOUT_INTEGRATION=true, foram executados no Order:

```powershell
$env:SHOPSPHERE_TEST_POSTGRES_URL = 'jdbc:postgresql://localhost:5432/order_db'
$env:SHOPSPHERE_TEST_CHECKOUT_INTEGRATION = 'true'
.\gradlew.bat test --offline --no-daemon --tests '*CheckoutFlowIntegrationTests'
.\gradlew.bat test --offline --no-daemon --rerun-tasks
```

A primeira execução integrada teve três falhas: o proxy atrasava também o 409
legítimo da preparação concorrente; Open EntityManager in View no Order causava
conflitos com estado desatualizado ao registrar aborto e disputar a mesma chave.
O proxy passou a bloquear somente respostas de preparação bem-sucedidas e o
Order passou a configurar spring.jpa.open-in-view=false. Os mesmos 11 casos
passaram na repetição, sem relaxar os contratos de aborto ou idempotência.
Foram acrescentados depois os cenários de resposta pública perdida e aborto
antes de preparação, incluídos na execução completa.

A execução completa com PostgreSQL e integração ativada aprovou **223 testes**,
zero falhas/erros e zero ignorados, incluindo **13 cenários integrados**. Os
quatro serviços iniciaram com seus JARs reais; o worker agendado retomou as
intenções sem replay público nos casos de restauração/reinício. A única mudança
posterior no código de teste foi esclarecer um comentário, sem efeito funcional.

Validação complementar executada:

- Catalog: `.\gradlew.bat test --offline --no-daemon --tests '*ProductBatchContractTests'`;
  sete testes aprovados.
- Gateway: `.\gradlew.bat test --offline --no-daemon --tests '*GatewayCartCheckoutIsolationTests' --tests '*GatewaySecurityTests'`;
  27 testes aprovados.
- Cart, com SHOPSPHERE_TEST_POSTGRES_URL apontando para cart_db:
  `.\gradlew.bat test --offline --no-daemon --tests '*CartCheckoutTests' --tests '*CartCheckoutPersistenceTests' --tests '*CartCheckoutJwtSecurityTests'`;
  61 testes aprovados e um opcional de token real do Keycloak ignorado, sem falhas.

Os testes integrados exercitam processos, HTTP, persistência, filtros/decoders
JWT, aquisição de token e agendamento reais. O emissor/JWKS/token endpoint e os
proxies de falhas pertencem ao harness. Não houve consulta/provisionamento do
Keycloak real ou login do frontend. Sem novas migrations/dependências e sem
ativação do modo snapshot no ambiente normal.
