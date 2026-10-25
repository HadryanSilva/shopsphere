# ShopSphere — Especificações e contexto para Codex

> **Finalidade:** documento de transferência de contexto para o Codex trabalhar no projeto ShopSphere. Consolida decisões e exemplos discutidos nesta conversa. **Não é uma descrição verificada do repositório**: antes de modificar código, inspecione os arquivos reais, versões, entidades, migrations e contratos. Em caso de divergência, preserve o código existente e peça esclarecimento quando a mudança puder quebrar contratos.

## 1. Visão geral

ShopSphere é um projeto educacional de **e-commerce distribuído em microsserviços**, voltado ao estudo de Java/Spring Boot, integração síncrona e assíncrona, autenticação, segurança, resiliência, transações e consistência distribuída.

- Linguagem: **Java** (Java moderno; verificar versão no projeto).
- Framework: **Spring Boot 4**, **Spring Framework 7**.
- Build: **Gradle**; conferir se cada módulo usa Groovy DSL (`build.gradle`) ou Kotlin DSL (`build.gradle.kts`).
- Persistência: **PostgreSQL**, **Spring Data JPA/Hibernate**, **Flyway**.
- API Gateway: **Spring Cloud Gateway Server Web MVC** (**não** WebFlux/Reactive).
- Segurança: **Spring Security**, **OAuth2 Resource Server**, **Keycloak**, JWT.
- HTTP interno: **Spring Cloud OpenFeign**.
- Mensageria planejada: **RabbitMQ**.
- Infra local: **Docker Compose**.
- Cliente web previsto: **Angular**, autenticação com Authorization Code + PKCE.
- Moeda inicial: **EUR**.

## 2. Serviços e responsabilidades

| Serviço | Responsabilidade | Porta local de exemplo |
|---|---|---|
| API Gateway | Entrada externa, roteamento e validação JWT | 8080 |
| Catalog Service | Produtos, categorias, busca, status e preço atual | 8081 |
| Cart Service | Carrinho ativo por cliente, itens e quantidades | 8082 |
| Order Service | Checkout, criação de pedidos, snapshot financeiro, ciclo de vida | 8083 |
| Inventory Service | Verificação e reserva de estoque | A definir |
| Payment Service | Processamento/coordenação de pagamentos | A definir |
| Notification Service | Notificações de eventos | A definir |
| Keycloak | Identidade e emissão de JWT | 8180 no host (8080 no container) |

Cada serviço possui o seu próprio banco/schema de domínio. **Não criar foreign keys entre bancos de microsserviços**. IDs externos são referências lógicas e validados via contratos de serviço.

## 3. Segurança e identidade

### Keycloak

- Realm: `shopsphere`.
- Emissor local: `http://localhost:8180/realms/shopsphere`.
- Cliente web público: `shopsphere-web`, Authorization Code + PKCE (`S256`), sem client secret no frontend.
- Roles inicialmente previstas: `CUSTOMER`, `ADMIN`.
- Audience pretendida para APIs: `shopsphere-api` (exige mapper/configuração no Keycloak se a validação de audiência estiver ativada).
- Em desenvolvimento, credenciais de bootstrap do Keycloak podem ser `admin/admin` **apenas localmente**; nunca para produção.

### Spring Boot 4

Usar o starter introduzido pelo Initializr:

```groovy
implementation 'org.springframework.boot:spring-boot-starter-security-oauth2-resource-server'
```

Não adicionar o starter antigo do Boot 3 apenas por exemplos antigos. O Resource Server valida tokens recebidos; o OAuth2 Client é necessário somente para fluxos de login/token acquisition apropriados (ex.: client credentials).

Exemplo de configuração em cada API:

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: http://localhost:8180/realms/shopsphere
```

Se a aplicação exigir audiência, configurar e validar `shopsphere-api` **somente após** garantir que os JWTs a contenham.

- Gateway e serviços de domínio **validam JWT independentemente**; não confiar apenas na segurança do Gateway.
- Mapear `realm_access.roles` para authorities `ROLE_...` se usar `hasRole`, porque o mapeamento não é automático no conversor padrão.
- Catálogo: GET de produtos/categorias público; operações de escrita restritas a `ADMIN`.
- Carrinho e pedidos: autenticados; validar propriedade do recurso no serviço.
- `@AuthenticationPrincipal Jwt jwt` permite obter `jwt.getSubject()`; neste estágio o **UUID `sub` do Keycloak é usado como `customerId`**. Se for criado um Customer Service com IDs próprios, será necessária uma associação explícita entre os dois IDs.
- **Não receber `customerId` arbitrário do frontend** para operar carrinho/pedido do usuário.
- Para chamadas de serviço em nome do usuário (ex.: Order → Cart `/me`), propagar explicitamente o Bearer token quando adequado. Para operações estritamente internas, usar identidade de serviço/client credentials e políticas específicas.
- Cuidado com o `issuer-uri` quando aplicações e Keycloak rodam em containers: `localhost` refere-se ao próprio container. O `iss` do token deve coincidir com o issuer configurado. Definir estratégia de hostname/rede consistente.

## 4. API Gateway MVC

Utilizar **Gateway Server Web MVC**. Exemplo conceitual de roteamento Java (verificar imports e API exata da versão instalada):

```java
import static org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http;
import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.uri;
import static org.springframework.web.servlet.function.RequestPredicates.path;

@Bean
RouterFunction<ServerResponse> routes() {
    return route("catalog-service")
        .route(path("/api/v1/products/**"), http())
        .route(path("/api/v1/categories/**"), http())
        .before(uri("http://localhost:8081"))
        .build()
        .and(route("cart-service")
            .route(path("/api/v1/carts/**"), http())
            .before(uri("http://localhost:8082"))
            .build())
        .and(route("order-service")
            .route(path("/api/v1/orders/**"), http())
            .before(uri("http://localhost:8083"))
            .build());
}
```

**Compatibilidade importante:** nesta versão do Gateway MVC, `http()` **não aceita URL**. A forma `http("http://...")` gera `Expected no arguments but found 1`; usar `http()` e `.before(uri(...))`. Preferir URLs externalizadas em propriedades (`services.catalog.url`, etc.).

## 5. OpenFeign

Padrão de integração interna direta (não passar pelo Gateway sem motivo):

```java
@FeignClient(
    name = "catalog-service",
    url = "${services.catalog.url}",
    path = "/api/v1/products"
)
public interface CatalogClient {
    @GetMapping("/{productId}")
    ProductResponse getProduct(@PathVariable("productId") UUID productId);
}
```

`name` = identificador lógico/hostname válido; `url` = base HTTP; `path` = prefixo de endpoint. **Nunca** definir `@FeignClient("/api/v1/products")`: provoca `Service id not legal hostname (/api/v1/products)` durante o startup.

URLs locais sugeridas:

```yaml
services:
  catalog:
    url: http://localhost:8081
  cart:
    url: http://localhost:8082
  order:
    url: http://localhost:8083
```

No Docker Compose, usar nomes DNS de serviço (por exemplo `http://catalog-service:8081`) quando corretos para a rede.

## 6. Catalog Service

### Entidades e contratos

`ProductResponse` discutido:

```java
public record ProductResponse(
    UUID id,
    String name,
    String description,
    UUID categoryId,
    BigDecimal price,
    Currency currency,
    String imgUrl,
    ProductStatus status,
    Instant createdAt,
    Instant updatedAt
) {}
```

Verificar tipo real de `Currency` no código (pode ser enum de domínio ou `java.util.Currency`). Evitar hardcode `EUR` no mapper se a entidade possui moeda persistida.

Busca: `GET /api/v1/products`, com paginação e filtros opcionais (`page`, `size`, `sort`, `categoryId`, `minPrice`, `maxPrice`, `name`, `status`). Implementação prevista com `JpaSpecificationExecutor`, `Pageable` e whitelist de campos de ordenação (`name`, `price`, `createdAt`).

### Migration de referência — V1

Tabelas PostgreSQL:

- `categories`: `id UUID PK`, `name VARCHAR(100) NOT NULL UNIQUE`, `description VARCHAR(500)`, `created_at`, `updated_at` `TIMESTAMPTZ`.
- `products`: `id UUID PK`, `name VARCHAR(150)`, `description VARCHAR(2000)`, `category_id UUID NOT NULL FK categories(id)`, `price NUMERIC(19,2)`, `currency VARCHAR(3) DEFAULT 'EUR'`, `image_url VARCHAR(2048)`, `status VARCHAR(20) DEFAULT 'ACTIVE'`, timestamps.
- Checks: `price > 0`, moeda com 3 letras maiúsculas, status `ACTIVE`, `INACTIVE`, `OUT_OF_STOCK` (confirmar enum real).
- Índices por `category_id`, `status`, `name`.

### Migration de referência — V2

Seed de 10 categorias: Electronics, Clothing, Home & Kitchen, Books, Sports & Outdoors, Beauty & Personal Care, Toys & Games, Food & Beverages, Health & Wellness, Pet Supplies. IDs estáveis no padrão `10000000-0000-0000-0000-000000000001` até `...0010`. **Confirmar se migrations já existem; nunca reescrever migrations aplicadas.**

### Endpoint em lote planejado para checkout

Para evitar N chamadas ao catálogo:

```http
POST /api/v1/products/batch
Content-Type: application/json

{"productIds":["<uuid>","<uuid>"]}
```

Resposta prevista: coleção com `id`, `name`, `price`, `currency`, `status`. Esse endpoint **é proposta**, não assumir implementado. Definir autorização para chamadas internas (token do serviço ou política explícita).

## 7. Cart Service

### API

- `GET /api/v1/carts/me` — carrinho ativo do usuário autenticado.
- `POST /api/v1/carts/me/items` — adicionar produto/quantidade.
- `PATCH /api/v1/carts/me/items/{productId}` — atualizar quantidade.
- `DELETE /api/v1/carts/me/items/{productId}` — remover item.

### Modelo

`Cart`:
- `id UUID` (BaseEntity), `customerId UUID` persistido, `status CartStatus` inicialmente `ACTIVE`, `version BIGINT` (`@Version`), timestamps.
- `@OneToMany(mappedBy="cart", cascade=ALL, orphanRemoval=true)` para `CartItem`.
- `CartItem`: `id UUID`, `cart_id UUID`, `product_id UUID`, `quantity INTEGER > 0`, timestamps.
- `addItem(productId, quantity)` incrementa quantidade quando produto já existe; `removeItem` e `clear` controlam o agregado.
- `customerId` vem do JWT, **mas é persistido em `carts.customer_id`** para localizar o carrinho. Recomendado `updatable=false` e não expor setter público.
- `@Version` no `Cart` **pode não ser incrementado ao alterar somente filhos**; não assumir que resolve todas as condições de corrida.

### Flyway

`V1__create_initial_schema.sql`:

```sql
CREATE TABLE carts (
    id UUID PRIMARY KEY,
    customer_id UUID NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_carts_status CHECK (status IN ('ACTIVE','CHECKED_OUT','ABANDONED'))
);
CREATE TABLE cart_items (
    id UUID PRIMARY KEY,
    cart_id UUID NOT NULL REFERENCES carts(id),
    product_id UUID NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_cart_customer ON carts(customer_id);
CREATE INDEX idx_cart_items_cart ON cart_items(cart_id);
CREATE INDEX idx_cart_items_product ON cart_items(product_id);
```

`V2__create_cart_business_constraints.sql`:

```sql
CREATE UNIQUE INDEX uk_carts_active_customer
    ON carts(customer_id) WHERE status = 'ACTIVE';
ALTER TABLE cart_items ADD CONSTRAINT uk_cart_items_cart_product
    UNIQUE (cart_id, product_id);
```

**Verificar enums reais** antes de aplicar checks. Uma constraint de unicidade evita duplicação no banco, mas o código deve tratar corrida de criação e violação de constraint.

### Integrações

- Cart → Catalog por Feign ao adicionar item, para verificar existência e estado do produto.
- Carrinho persiste apenas `productId` e quantidade; nomes e preços são enriquecidos na leitura via Catalog, sem snapshot monetário permanente.
- No checkout, Order revalida preços e guarda snapshots próprios.
- Futuro: endpoint de snapshot/lock do carrinho para impedir alterações concorrentes durante checkout; projetar transições e compensações.

## 8. Order Service — escopo atual prioritário

O usuário iniciou a implementação do módulo Order e solicitou aprofundamento **do endpoint até as validações e comunicações externas**. Implementar incrementalmente; distinguir o que já foi discutido do que ainda é proposto.

### API inicial

```http
POST /api/v1/orders
Authorization: Bearer <JWT>
Idempotency-Key: <UUID>
Content-Type: application/json

{}
```

- Não receber `customerId` no body: derivar de `jwt.getSubject()`.
- Itens do pedido vêm do carrinho ativo, não de preços fornecidos pelo frontend.
- Sucesso inicial: `201 Created`, `Location: /api/v1/orders/{id}`, resposta com `id`, `status`, `totalAmount`, `currency`, itens e snapshots.
- `GET /api/v1/orders/{id}` para consultar status; verificar que `customerId` do pedido coincide com o usuário autenticado (ou role administrativa autorizada).
- `Idempotency-Key` deve ser estável em retries do mesmo checkout; chave nova para nova intenção de compra.

### Sequência de checkout proposta

1. Gateway valida JWT; Order Service também valida JWT.
2. Controller extrai `customerId` do `sub` e recebe `Idempotency-Key`.
3. Verificar idempotência por `(customer_id, idempotency_key)`; se já processada, devolver resultado anterior. Em concorrência, garantir unicidade no banco e tratar conflito.
4. Order → Cart: consultar carrinho do usuário (`GET /api/v1/carts/me`) com Bearer token propagado quando o contrato exige usuário; verificar existência, status ativo, itens não vazios, quantidades > 0 e sem duplicados.
5. Order → Catalog: consultar em lote produtos por UUID; validar retorno completo, produtos ativos, preços válidos, moeda coerente e nomes disponíveis.
6. Calcular subtotais com `BigDecimal` (`unitPrice * quantity`) e total com `BigDecimal`; **não confiar no total do Cart nem em preços enviados pelo cliente**.
7. Construir `Order` e `OrderItem` com snapshot de `productName`, `unitPrice`, `quantity`, `totalPrice`.
8. Persistir Order, itens, chave de idempotência e evento de outbox **numa transação local curta**. **Não manter transação aberta durante Feign/HTTP**.
9. Retornar `201 Created` com estado `PENDING` (ainda não significa compra confirmada).
10. Publicador Outbox envia `OrderCreated` ao RabbitMQ; Inventory reserva estoque; Payment executa etapa posterior; atualizar estados com eventos e compensações (Saga).

### Feign Cart

```java
@FeignClient(name="cart-service", url="${services.cart.url}")
public interface CartClient {
    @GetMapping("/api/v1/carts/me")
    CartResponse getMyCart(@RequestHeader("Authorization") String authorization);
}
```

Encapsular obtenção do token num adapter/componente de infraestrutura; evitar acoplar regras de domínio a `SecurityContextHolder`. Para chamadas sem contexto do usuário, adotar identidade de serviço.

### Feign Catalog

```java
@FeignClient(name="catalog-service", url="${services.catalog.url}")
public interface CatalogClient {
    @PostMapping("/api/v1/products/batch")
    BatchProductsResponse getProducts(@RequestBody BatchProductsRequest request);
}
```

O endpoint batch é **planejado**; confirmar existência e contratos antes de codificar.

### Schema inicial de Order — `V1__create_initial_schema.sql`

```sql
CREATE TABLE orders (
    id UUID PRIMARY KEY,
    customer_id UUID NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    total_amount NUMERIC(19,2) NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'EUR',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT chk_orders_status CHECK (status IN ('PENDING','CONFIRMED','CANCELLED','COMPLETED')),
    CONSTRAINT chk_orders_total_amount CHECK (total_amount >= 0),
    CONSTRAINT chk_orders_currency CHECK (currency ~ '^[A-Z]{3}$')
);
CREATE TABLE order_items (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL,
    product_id UUID NOT NULL,
    product_name VARCHAR(150) NOT NULL,
    quantity INTEGER NOT NULL,
    unit_price NUMERIC(19,2) NOT NULL,
    total_price NUMERIC(19,2) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_order_items_order FOREIGN KEY (order_id) REFERENCES orders(id),
    CONSTRAINT chk_order_items_quantity CHECK (quantity > 0),
    CONSTRAINT chk_order_items_unit_price CHECK (unit_price >= 0),
    CONSTRAINT chk_order_items_total_price CHECK (total_price >= 0),
    CONSTRAINT uk_order_items_order_product UNIQUE (order_id, product_id)
);
CREATE INDEX idx_orders_customer_id ON orders(customer_id);
CREATE INDEX idx_orders_status ON orders(status);
CREATE INDEX idx_orders_customer_created_at ON orders(customer_id, created_at DESC);
CREATE INDEX idx_order_items_order_id ON order_items(order_id);
CREATE INDEX idx_order_items_product_id ON order_items(product_id);
```

**Nota:** enum e checks são decisões iniciais e devem acompanhar o modelo real. `Order` é aggregate root; `OrderItem` pertence ao pedido e não é atualizado por alterações posteriores do Catalog. O índice `idx_order_items_order_id` pode ser redundante com o índice criado pela constraint única `(order_id, product_id)` — revisar com `EXPLAIN` se necessário.

### Migration proposta V2 — idempotência

```sql
ALTER TABLE orders ADD COLUMN idempotency_key UUID;
CREATE UNIQUE INDEX uk_orders_customer_idempotency
    ON orders(customer_id, idempotency_key);
```

Coluna nullable por compatibilidade com dados já existentes; numa instalação nova pode-se exigir NOT NULL. Em caso de mesma chave com payload/checkout incompatível, adotar resposta de conflito em vez de reaproveitar indevidamente pedido. Tratar race condition e falha de unique index em transação independente. **Não alterar V1 se já aplicada.**

### Migration proposta V3 — Transactional Outbox

```sql
CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    aggregate_id UUID NOT NULL,
    aggregate_type VARCHAR(100) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ,
    CONSTRAINT chk_outbox_status CHECK (status IN ('PENDING','PUBLISHED','FAILED'))
);
CREATE INDEX idx_outbox_events_pending
    ON outbox_events(created_at) WHERE status = 'PENDING';
```

Inserir `orders` e `outbox_events` na **mesma transação**. Publicador separado faz publish com confirmações do broker, retries e marcação de publicação. Outbox entrega **at least once**: consumidores precisam deduplicar por `eventId`. Adicionar metadados, tentativas e locks conforme necessidade.

### Estados de pedido

Versão inicial: `PENDING`, `CONFIRMED`, `CANCELLED`, `COMPLETED`. Evolução prevista: `AWAITING_STOCK`, `AWAITING_PAYMENT`, `PAID`, `FULFILLED` e estados de falha. Não marcar `CONFIRMED` antes de as pré-condições de negócio estarem satisfeitas.

### Riscos e decisões pendentes

- **Carrinho concorrente:** enquanto Order lê o carrinho, cliente pode alterá-lo. Proposta: snapshot consistente e bloqueio/transição de checkout no Cart Service; tratar expiração, retomada e compensação.
- **Estoque:** produto ativo no Catalog não equivale a estoque disponível. Reserva pertence ao Inventory Service.
- **Pagamento:** não capturar pagamento sem estratégia clara para estoque/reserva e compensação; evitar transação distribuída de banco.
- **Segurança interna:** endpoints de batch/catalog e checkout devem ter permissões adequadas; JWT do usuário somente onde semanticamente apropriado.
- **Resiliência Feign:** timeouts, erros tipados, retries apenas quando seguros, circuit breaker opcional; não repetir POST de criação sem idempotência.
- **Precisão monetária:** `BigDecimal`, escala e arredondamento consistentes, EUR inicial, sem double/float.
- **Auditoria e observabilidade:** correlation/request IDs, tracing, logs sem tokens/dados sensíveis, métricas de latência, erros e eventos pendentes.
- **Testes:** unitários de agregados e validações, integração JPA/Flyway/PostgreSQL, contratos HTTP, segurança 401/403, concorrência/idempotência e integração com RabbitMQ.

## 9. Sequência de implementação recomendada

1. Conferir o repositório e identificar o estado real dos módulos, entidades, enums, configurações e migrations.
2. Implementar entidades `Order`, `OrderItem`, DTOs, repositórios e mapeamentos JPA alinhados à V1.
3. Implementar `POST /api/v1/orders` e `GET /api/v1/orders/{id}` com autenticação e autorização por proprietário.
4. Implementar adapters Feign para Cart e Catalog, contratos e tratamento de falhas.
5. Implementar validação de carrinho/produtos, cálculo financeiro e persistência do pedido.
6. Adicionar idempotência de checkout com V2 e testes de concorrência.
7. Projetar snapshot/lock de carrinho para evitar alterações durante checkout.
8. Adicionar Outbox com V3, publicador RabbitMQ e testes de falha.
9. Implementar Inventory, Payment, eventos e compensações Saga gradualmente.

## 10. Convenções e instruções para o Codex

- **Responder e documentar em português**, mas manter nomes de código, classes, tabelas e endpoints em inglês.
- Antes de criar arquivos, **inspecionar o repositório** e respeitar os packages, arquitetura e convenções existentes. O package de Catalog já apareceu como `br.com.hadryan.shopsphere.catalog`; confirmar os packages dos demais serviços.
- Separar responsabilidades de controller, use case/application, domínio, repositório e adapters de integração; evitar regras de negócio em controllers/Feign clients.
- Preferir DTOs imutáveis (`record`) e `BigDecimal` para valores financeiros.
- Manter Flyway como fonte de verdade do schema; `spring.jpa.hibernate.ddl-auto=validate` quando apropriado.
- Não modificar migrations Flyway já executadas; criar novas migrations para evoluções.
- Não introduzir WebFlux/Reactor no Gateway MVC sem solicitação explícita.
- Não supor que classes/endpoints propostos já foram implementados.
- Usar testes para demonstrar casos felizes, erros de negócio, segurança, concorrência e integração.
- Não incluir secrets, tokens reais ou credenciais de produção em código ou documentação.
- Ao propor mudança de contrato ou schema, explicitar impacto nos serviços consumidores e migração necessária.

## 11. Checklist de verificação ao abrir o repositório

- [ ] Confirmar versões reais do Java, Boot, Spring Cloud e Gradle.
- [ ] Confirmar módulos existentes e suas portas.
- [ ] Verificar `@EnableFeignClients`, dependências e configurações de OpenFeign.
- [ ] Verificar SecurityFilterChain e conversor de roles do Keycloak.
- [ ] Verificar Gateway MVC (`http()` + `before(uri(...))`).
- [ ] Conferir enums de ProductStatus, CartStatus, OrderStatus e tipos monetários.
- [ ] Conferir BaseEntity, geração de UUID e auditoria timestamps.
- [ ] Verificar migrations aplicadas antes de propor V2/V3.
- [ ] Conferir contratos de Cart `/me` e Catalog batch.
- [ ] Confirmar se Order Service já possui idempotência, Outbox ou eventos.
- [ ] Confirmar estratégia de propagação de JWT e autenticação serviço-a-serviço.

---

**Resumo operacional para o Codex:** o foco imediato é desenvolver o **Order Service**, começando por checkout HTTP, integração Order → Cart/Catalog, validações e persistência transacional de snapshots. A evolução planejada inclui idempotência, consistência do carrinho, Transactional Outbox, RabbitMQ e Saga para Inventory/Payment. Todas as decisões descritas são o contexto discutido; valide-as contra o repositório antes de executar mudanças.
