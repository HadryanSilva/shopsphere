# Adição ao carrinho ativo — microtarefa 13

`POST /api/v1/carts/me/items` aceita `{"productId":"<uuid>","quantity":2}`.
O cliente vem do JWT. `cartId` passou de obrigatório a opcional: consumidores
existentes podem continuar enviando-o, mas ele precisa identificar um carrinho
ativo do próprio usuário. Carrinhos ausentes, históricos ou de terceiros
retornam `404` quando um ID explícito é informado.

Sem `cartId`, a adição cria ou recupera o carrinho ativo do cliente. O retorno
continua sendo `200` com o ID do carrinho utilizado. Produto e quantidade são
obrigatórios; a quantidade deve ser positiva.

O catálogo é consultado antes da transação de escrita. Uma transação local
separada cria o carrinho com `INSERT ... ON CONFLICT DO NOTHING`, usando o
índice parcial já existente, e bloqueia o carrinho para adicionar o item.
Assim, primeiras adições concorrentes usam um único carrinho e adições ao
mesmo produto não perdem quantidades. A tarefa 16 estende esse bloqueio às
gravações de atualização e remoção; o protocolo de checkout continua pendente.
Nenhuma migração nova é necessária.

Os testes unitários e HTTP executam com `./gradlew.bat test`. A integração
`ActiveCartCreationTests` usa PostgreSQL real, Flyway, Hibernate e transações
Spring. Inclui quatro primeiras adições simultâneas, isolamento por cliente,
reutilização de carrinho e verificação de que o catálogo é chamado sem transação.
O teste usa schemas temporários removidos ao final.

```powershell
$env:SHOPSPHERE_TEST_POSTGRES_URL = 'jdbc:postgresql://localhost:5432/cart_db'
$env:SHOPSPHERE_TEST_POSTGRES_USER = 'postgres'
$env:SHOPSPHERE_TEST_POSTGRES_PASSWORD = 'postgres'
.\gradlew.bat test --rerun-tasks
```

Sem a variável de URL, somente os testes de integração com PostgreSQL são
ignorados.

## Consulta do carrinho ativo — microtarefa 14

`GET /api/v1/carts/me` busca o carrinho pelo cliente autenticado e pelo status
`ACTIVE`. Carrinhos históricos não são retornados nem usados no enriquecimento
de produtos. Os itens são carregados junto com o carrinho por `EntityGraph`,
sem exigir sessão JPA aberta ou transação durante a chamada ao catálogo.

Sem carrinho ativo, a API retorna `404` com detalhe `Cart not found`, conforme
a tarefa 22. Não há alteração do payload de sucesso,
rotas, criação automática na leitura ou migrações nesta tarefa.

`ActiveCartCreationTests` também verifica a leitura com carrinhos históricos
`ABANDONED`/`CHECKED_OUT` e carrinho ativo de outro cliente, além do cenário
com apenas histórico e nenhum carrinho ativo para o cliente consultado.

## Status do carrinho — microtarefa 15

O enum usa `ACTIVE`, `CHECKED_OUT` e `ABANDONED`, os valores aceitos pela
constraint `chk_carts_status` da V1. `CONVERTED`, que era rejeitado pelo banco
e não possuía outros usos no código, foi substituído por `CHECKED_OUT`.
Não há nova migração, conversão de dados ou alteração dos endpoints. A leitura
de `/me` continua retornando somente `ACTIVE`; a transição de checkout ainda
não foi implementada nesta tarefa.

O teste parametrizado de `ActiveCartCreationTests` grava cada estado pelo JPA,
recarrega em outra sessão e confere o valor diretamente no PostgreSQL,
preservando os itens do carrinho. Antes da correção, o caso `CONVERTED` falhou
na constraint; com o enum alinhado, todos os estados devem ser aceitos.

## Atualização e remoção por produto — microtarefa 16

- `PATCH /api/v1/carts/me/items/{productId}` recebe apenas `{"quantity":3}`.
  A quantidade é obrigatória e positiva; substitui a anterior. Repetir o
  mesmo PATCH mantém a quantidade, sem incrementá-la. Produto ausente no
  carrinho ativo retorna `404`, sem criar um item.
- `DELETE /api/v1/carts/me/items/{productId}` não exige corpo. A remoção de
  um item já ausente continua sem efeito, com retorno `204` se existir carrinho
  ativo. A ausência de carrinho ativo retorna `404` nas duas operações.

As duas operações usam o carrinho `ACTIVE` do usuário do JWT e retornam `204`
no sucesso. O identificador da URL passou de carrinho para produto; consumidores
do contrato anterior precisam adaptar a URL e os corpos. O frontend não escolhe
o carrinho a ser alterado. Nenhuma alteração de schema é necessária.

A atualização verifica o produto no catálogo fora da transação e revalida a
existência do item sob bloqueio na gravação. Adição, atualização e remoção usam
o mesmo bloqueio de carrinho para serializar suas gravações. Isso não implementa
snapshot ou bloqueio de checkout. Os testes HTTP cobrem o novo contrato e as
quantidades inválidas; a integração verifica PATCH repetido, remoção persistida
e preservação do carrinho de outro cliente e do histórico.

## Erros padronizados — microtarefa 22

O contrato está em `../contracts/api-errors.md`. Produto inativo retorna `409`,
recurso inexistente retorna `404`, e falhas de integração têm `502/503` conforme
o tipo. Erros de validação incluem campo e mensagem sem valor rejeitado.
`ApiExceptionHandlerTests` e `CartServiceApplicationTests` cobrem o formato e
os fluxos HTTP; a suíte de persistência preserva os testes de rollback e isolamento.

## Limite de quantidade acumulada — microtarefa 26

O POST de itens aceita quantidades positivas até `2147483647`, o limite de
`Integer` e da coluna PostgreSQL existente. Uma primeira adição nesse limite
ou um incremento que o atinja continua válido. Se a soma com a quantidade já
presente exceder o limite, a API retorna `409` em `application/problem+json`,
com detalhe `Cart item quantity exceeds supported limit`, sem alterar o item.
Quantidades nulas, não positivas ou fora do tipo Integer no JSON continuam
retornando `400`; não foi introduzido um limite comercial menor.

A soma usa `Math.addExact` no agregado. O componente transacional traduz esse
overflow específico para conflito, sob o bloqueio de carrinho já existente e
antes de gravar. Outros erros de argumento/estado não são tratados como overflow.
Não há mudanças no PATCH, payloads, schema, migrations ou segurança.

`CartQuantityTests` verifica primeira adição máxima, incremento até o máximo e
rejeições sem alteração dos itens. `CartServiceApplicationTests` verifica o
HTTP 409 com agregado/caso de uso reais e repositório simulado.
`ActiveCartCreationTests` verifica no PostgreSQL a quantidade persistida após
overflow, com e sem cartId explícito. Use os comandos e variáveis PostgreSQL
descritos no início deste documento. Para os testes focados sem banco:

```powershell
.\gradlew.bat test --tests '*CartQuantityTests' --tests '*CartServiceApplicationTests' --tests '*AddCartItemTransactionTests' --offline --no-daemon
```

Validação desta entrega: testes focados aprovados; a suíte completa com
`SHOPSPHERE_TEST_POSTGRES_URL` apontando para `cart_db` local, executada com
`.\gradlew.bat test --offline --no-daemon --rerun-tasks`, aprovou os 67 testes,
sem falhas ou ignorados, incluindo persistência e concorrência.

## Consulta em lote ao Catalog — microtarefa 27

A leitura de um carrinho não vazio faz uma única chamada
`GET /api/v1/products/batch?productIds=...`, reutilizando o contrato existente
do Catalog. Um carrinho vazio não consulta o Catalog. A resposta é indexada
por productId; a ordem dos itens continua sendo a do carrinho, mesmo que o
batch devolva produtos em outra ordem. Quantidades, subtotais, total estimado,
moeda e formato da resposta permanecem iguais. Produtos inativos ou sem estoque
continuam visíveis na leitura, conforme o comportamento anterior.

O batch pode omitir produtos inexistentes. Se faltar qualquer produto do
carrinho, a leitura retorna `404` com `Product not found.`, sem resposta parcial.
Erros Feign continuam usando o contrato existente: 404 do Catalog vira 404,
falhas HTTP sem tratamento específico viram 502 e RetryableException vira 503.
Resposta batch nula, elementos nulos, ID/preço nulos ou IDs duplicados retornam
502; não é escolhido silenciosamente um dos produtos duplicados.

O cliente Catalog do Cart usa connectTimeout de 2000 ms e readTimeout de
5000 ms em `application.yaml`, incluindo as chamadas individuais de adição e
PATCH. O retry automático permanece desativado pelo padrão Spring Cloud
OpenFeign. Não foi adicionado cache nem paralelismo, e a chamada permanece fora
de transações. No checkout de N produtos, as chamadas ao Catalog passam de
N + 1 para duas: uma pelo Cart e outra pelo Order, que mantém sua revalidação.

`GetCartUseCaseImplTests` cobre batch único, ordem, totais, ausência de produto,
duplicatas e carrinho vazio. `CartServiceApplicationTests` cobre os erros HTTP.
`CatalogClientContractTests` usa o cliente Feign configurado pela aplicação,
com transporte simulado, para verificar query/body, resposta compartilhada,
timeouts efetivos e ausência de retry. `ActiveCartCreationTests` confirma leitura
com PostgreSQL e batch sem transação ativa.

```powershell
.\gradlew.bat test --tests '*GetCartUseCaseImplTests' --tests '*CartServiceApplicationTests' --tests '*CatalogClientContractTests' --offline --no-daemon
```

Não há alterações nos endpoints do Catalog, DTOs públicos, regras de segurança,
migrations, dependências ou revalidação do Order. A consulta usa o GET batch
existente, estando sujeita aos limites de tamanho de URL do servidor/proxy;
esta tarefa não introduz limite de itens nem altera esse contrato para POST.

Validação da entrega: testes focados aprovados; suíte completa com PostgreSQL
local (`.\gradlew.bat test --offline --no-daemon --rerun-tasks`) com 76 testes
aprovados, sem falhas ou ignorados. Também passaram os 7 testes de contrato
batch do Catalog e os 2 testes do cliente Catalog do Order, executados com
`--tests '*ProductBatchContractTests'` e `--tests '*CatalogClientContractTests'`
nos respetivos módulos.

## Preparação local do checkout — microtarefa 43

Modelo, arquivos e limites em [CHECKOUT_PERSISTENCE.md](CHECKOUT_PERSISTENCE.md).
Novos testes: CartCheckoutTests e CartCheckoutPersistenceTests, além de um
cenário HTTP com os componentes reais de escrita em CartServiceApplicationTests.
Os contextos sem datasource também simulam CartCheckoutRepository; os testes
PostgreSQL continuam usando os repositórios reais.

Executados no módulo Cart:

```powershell
.\gradlew.bat test --offline --no-daemon --tests '*CartItemOwnershipTests' --tests '*AddCartItemTransactionTests' --tests '*CartServiceApplicationTests'
$env:SHOPSPHERE_TEST_POSTGRES_URL = 'jdbc:postgresql://localhost:5432/cart_db'
.\gradlew.bat test --offline --no-daemon --tests '*CartCheckoutTests' --tests '*CartCheckoutPersistenceTests'
.\gradlew.bat test --offline --no-daemon --rerun-tasks
```

Os testes existentes focados passaram. A primeira execução dos testes novos
revelou diferença de precisão de timestamps após persistência e obtenção de
bean pela classe concreta em vez da interface transacional. As causas foram
corrigidas: timestamps de checkout usam microssegundos e o teste usa a interface.
A repetição focada aprovou 22 testes; após acrescentar cobertura HTTP e snapshot
com 101 itens, a suíte completa aprovou **100 testes**, zero falhas/erros e zero
ignorados. São 14 testes PostgreSQL novos e nove casos unitários de checkout.

Validados: upgrade de V2 com dados, Hibernate validate, reinício, snapshot
imutável, replay/timestamps, isolamento de cliente de serviço, quatro caminhos
de escrita bloqueados, duas intenções para o mesmo carrinho, mesma intenção
concorrente, edição versus preparação, aborto versus preparação/conclusão,
aborto antecipado e criação de novo ACTIVE após conclusão. Constraints SQL e
falha de conclusão verificam atomicidade e rollback sem perder a preparação.

PostgreSQL estava inicialmente indisponível; tornou-se acessível após aviso ao
utilizador e foi utilizado na execução final. A tentativa inicial do wrapper
sem elevação falhou ao acessar a cache Gradle; execuções seguintes foram
autorizadas. V3 foi aplicada somente em schemas temporários de teste, removidos
pelo procedimento de limpeza existente. Nenhuma base compartilhada, configuração
de Keycloak ou integração Order foi alterada. Não há endpoint interno nesta etapa.

## Protocolo interno e identidade de serviço — microtarefa 44

Contratos, segurança, arquivos e reprodução Keycloak em [CHECKOUT_API.md](CHECKOUT_API.md).
CartCheckoutJwtSecurityTests utiliza controller e filtros reais, JWTs assinados,
JWKS HTTP local e decoder real do Spring Boot, com CartCheckoutService simulado.
Persistência, transições e isolamento do registro continuam cobertos pelos testes
PostgreSQL reais da etapa 43. O teste de token Keycloak usa o endpoint GET interno
com serviço simulado; não cria preparação num carrinho de uso normal.

Executados no módulo Cart:

```powershell
.\gradlew.bat test --offline --no-daemon --tests '*CartAddProductHttpTests' --tests '*CartServiceApplicationTests'
.\gradlew.bat test --offline --no-daemon --tests '*CartCheckoutJwtSecurityTests'
$env:SHOPSPHERE_TEST_POSTGRES_URL = 'jdbc:postgresql://localhost:5432/cart_db'
# SHOPSPHERE_TEST_CART_CHECKOUT_TOKEN fornecido apenas em memória pelo processo de validação.
.\gradlew.bat test --offline --no-daemon --rerun-tasks
```

Os testes focados passaram. O conjunto de JWT contabilizou 39 casos, com um
teste opcional de token real ignorado na execução inicial. A suíte completa,
com PostgreSQL e token Keycloak de client_credentials habilitados, aprovou
**139 testes**, zero falhas/erros e zero ignorados. As variáveis temporárias
de token e URL foram removidas ao terminar. As migrations existentes foram
exercitadas apenas nos schemas temporários de teste.

Validados: 201/Location versus 200 replay, consulta/conclusão/aborto, inclusão
de nulls, body e UUID inválidos, 404/409 MVC, ausência de sessão e identidade
derivada de azp sem confiar no sub/header. Matriz de role/azp, scopes e claims
malformados, audiência ausente/incorreta, issuer incorreto, expiração, assinatura
inválida e token malformado não concedem acesso. O teste público comprova que
a exigência adicional de audiência e os novos handlers não se aplicam ao Cart
público. Os demais testes HTTP públicos continuaram aprovados.

No módulo Gateway:

```powershell
.\gradlew.bat test --offline --no-daemon --tests '*GatewayCartCheckoutIsolationTests' --tests '*GatewaySecurityTests'
.\gradlew.bat test --offline --no-daemon
```

Conjunto focado e suíte completa aprovados; suíte completa: **86 testes**, zero
falhas/erros e zero ignorados. Quatro casos verificam ausência de encaminhamento
no predicado real e quatro verificam negação mesmo com ADMIN e role de serviço.

O provisionamento local foi executado com sucesso. Uma tentativa de reexecução
revelou detecção incorreta de role já existente na resposta de lista PowerShell;
a consulta passou a usar diretamente a role pelo nome e a reexecução passou.
Foram verificadas as atribuições e scopes anteriores e as claims não sensíveis
do token emitido, incluindo preservação de INVENTORY_SERVICE e ausência de ADMIN.
Não houve alteração de cliente web, segredo, mapper de audiência ou código Order.
