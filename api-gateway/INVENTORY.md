# Inventory pelo Gateway — microtarefa 40

O Gateway MVC expõe as cinco operações administrativas aprovadas. Todas
exigem realm role ADMIN; scopes OAuth2 ou INVENTORY_SERVICE isoladamente
não concedem esse acesso. O Inventory continua validando JWT, audiência e
permissões independentemente do Gateway.

| Método | Caminho encaminhado |
|---|---|
| POST | `/api/v1/inventory/items` |
| GET | `/api/v1/inventory/items/{productId}` |
| GET | `/api/v1/inventory/items/batch?productIds=...` |
| POST | `/api/v1/inventory/items/{productId}/adjustments` |
| GET | `/api/v1/inventory/items/{productId}/adjustments/{id}` |

Os predicados da rota restringem caminho e verbo. Não há rota `/internal/**`
nem rota externa de reservas. PUT/DELETE/PATCH/HEAD/OPTIONS e caminhos não
aprovados de Inventory continuam negados, inclusive para ADMIN. A proteção
do serviço e as restrições de rede do ambiente continuam necessárias para
chamadas diretas ao Inventory.

## Configuração e transporte

`services.inventory.url` define o destino. No perfil dev, o padrão é
`http://localhost:8084`, substituível por SHOPSPHERE_INVENTORY_URL. Em outros
ambientes, forneça services.inventory.url na configuração do Gateway,
assim como as URLs dos outros serviços. O perfil test usa um host `.invalid`;
os testes de transporte substituem a URL por um servidor HTTP local.

O handler de Inventory reutiliza ProxyExchangeHandlerFunction e
RestClientProxyExchange do Spring Cloud Gateway MVC, com os mesmos filtros
de headers. Seu cliente HTTP é próprio, com conexão e leitura limitadas,
sem alterar o cliente dos destinos Catalog/Cart/Order:

| Propriedade | Padrão em milissegundos | Variável no perfil dev |
|---|---|---|
| services.inventory.connect-timeout | 2000 | SHOPSPHERE_INVENTORY_CONNECT_TIMEOUT |
| services.inventory.read-timeout | 5000 | SHOPSPHERE_INVENTORY_READ_TIMEOUT |

Valores devem ser positivos. Não há filtro de retry; redirecionamentos não
são seguidos. Authorization é encaminhado porque a operação administrativa
atua em nome do principal autenticado. Idempotency-Key, corpo UTF-8,
caminho/query, status, corpo e Location do Inventory são preservados.

Erros HTTP do Inventory, inclusive Problem Details, são encaminhados sem
substituição. Falha de acesso HTTP/timeout antes da resposta retorna 503
com Problem Details genérico, sem URL interna ou detalhes da exceção.
Timeout não prova que uma escrita falhou no destino: uma nova tentativa
de cadastro/ajuste deve reutilizar a mesma Idempotency-Key.

## Validação e limites

GatewayInventoryRoutingTests inicia o Gateway em porta aleatória e um
servidor HTTP que simula Inventory. Usa decoder JWT simulado com filtros
reais. Verifica todas as rotas, batch com parâmetros repetidos, body UTF-8,
Authorization/Idempotency-Key, Location, respostas de erro, permissões,
negação das reservas internas e timeout sem repetir POST.

GatewayInventoryRoutePredicateTests verifica a seleção de rotas sem filtro
de segurança, para comprovar que os caminhos internos não têm encaminhamento.
InventoryProxyConfigTests verifica limites positivos, deadline de conexão e
redirecionamentos. A suíte completa inclui regressões de categorias e das
permissões existentes. Comandos/resultados em [TESTING.md](TESTING.md).

Não foi validada nesta etapa uma cadeia com Gateway, Inventory real e
Keycloak real. Não houve migration, alteração de DTOs, lógica de estoque,
configuração de Keycloak, dependências ou deploy. Nenhum caminho de reserva
foi tornado público. Observabilidade fica para a microtarefa 41.

## Arquivos alterados

Relativos a `api-gateway/`:

- `src/main/java/br/com/hadryan/shopsphere/gateway/config/GatewayRoutesConfig.java`
- `src/main/java/br/com/hadryan/shopsphere/gateway/config/SecurityConfig.java`
- `src/main/java/br/com/hadryan/shopsphere/gateway/config/InventoryProxyConfig.java` (novo)
- `src/main/resources/application-dev.yaml`
- `src/test/resources/application-test.yaml`
- `src/test/java/br/com/hadryan/shopsphere/gateway/config/GatewayInventoryRoutingTests.java` (novo)
- `src/test/java/br/com/hadryan/shopsphere/gateway/config/GatewayInventoryRoutePredicateTests.java` (novo)
- `src/test/java/br/com/hadryan/shopsphere/gateway/config/InventoryProxyConfigTests.java` (novo)
- `INVENTORY.md` (novo)
- `TESTING.md`

Também: `inventory-service/SECURITY.md`, `inventory-service/PLAN.md` e
`BACKLOG.md` na raiz do monorepo.
