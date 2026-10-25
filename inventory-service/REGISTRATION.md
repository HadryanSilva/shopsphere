# Cadastro de estoque — microtarefa 33

O cadastro aprovado está implementado em `POST /api/v1/inventory/items`.
Exige JWT válido com ADMIN e header UUID `Idempotency-Key`. O ator é o sub
autenticado, não um campo do body. Um sub ausente, vazio ou acima do limite de
255 caracteres é rejeitado como credencial inválida antes do caso de uso.

Exemplo de body, sem valores financeiros ou contadores de reserva:

```json
{"productId":"20000000-0000-0000-0000-000000000001","onHand":7}
```

onHand é obrigatório e aceita de zero a 2147483647. Sucesso retorna `201`,
Location `/api/v1/inventory/items/{productId}` e:

```json
{
  "productId":"20000000-0000-0000-0000-000000000001",
  "onHand":7,
  "reserved":0,
  "available":7,
  "version":0,
  "updatedAt":"2026-10-25T12:00:00Z"
}
```

O exemplo é ilustrativo. O GET desse Location pertence à microtarefa 35 e
ainda não está implementado nesta entrega. Não foram criados endpoints de
ajuste, reposição ou reservas, nem alteradas rotas do Gateway.

## Catalog e transação

1. Procurar um cadastro anterior pela chave e ator; um replay não consulta Catalog.
2. Rejeitar produto já cadastrado com outra chave, sem alterar seu saldo.
3. Consultar `GET /api/v1/products/{id}` diretamente no Catalog, sem encaminhar JWT.
4. Exigir resposta 200 com o mesmo ID, aceitando também produtos INACTIVE ou
   OUT_OF_STOCK. Não usar preço, moeda ou status como saldo de estoque.
5. Persistir posição e movimento INITIAL na mesma transação local REQUIRES_NEW.
6. Retornar somente depois de concluída a transação de escrita.

O adapter usa RestClient/JdkClientHttpRequestFactory da stack existente,
sem nova dependência de HTTP. O HttpClient é fechado pelo ciclo de vida Spring.
A URL dev é `services.catalog.url`, substituível por SHOPSPHERE_CATALOG_URL;
fora de dev/test, fornecer essa propriedade. Os timeouts padrão são 2000 ms
para conexão e 5000 ms para leitura, substituíveis por
`services.catalog.connect-timeout` e `services.catalog.read-timeout` (ms).
Valores não positivos são rejeitados no arranque. Não há retry de aplicação
para a consulta nem retry automático de INSERT, e redirects não são seguidos.

404 do Catalog vira 404. Demais erros HTTP/resposta inválida viram 502;
falhas de conexão/leitura viram 503. A API usa o Problem Details já existente,
sem devolver body interno do Catalog. Uma falha não cadastra estoque nem chave.

Referências do adapter: [RestClient](https://docs.spring.io/spring-framework/reference/integration/rest-clients.html)
e [JdkClientHttpRequestFactory](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/http/client/JdkClientHttpRequestFactory.html).

## Idempotência e concorrência

A V2 cria `inventory_movements`, sem editar a V1. O registro INITIAL guarda
o produto, delta inicial (inclusive zero), ator, chave, contadores originais e
created_at. A unicidade `uk_inventory_movements_actor_type_key` delimita a chave
por `(actor_subject,movement_type,idempotency_key)`. A FK aponta somente para
inventory_stock no mesmo banco. Não há FK ou leitura do banco do Catalog.

O tipo ADJUSTMENT e as colunas reason/contadores seguem o modelo aprovado, mas
esta entrega possui apenas a fábrica/fluxo INITIAL; ajustes serão implementados
na microtarefa 34. A V2 verifica tipos, limites dos contadores e coerência dos
snapshots INITIAL; ADJUSTMENT exige delta não zero e motivo não vazio.

O replay compara o productId e onHand com o registro INITIAL, nunca com o saldo
atual. Mesma chave/payload retorna 201, o mesmo Location e a posição atual,
sem resetar saldo nem gravar outro movimento. Mesma chave com produto/saldo
inicial diferentes retorna 409. A chave pode ser reutilizada por outro ator
para outro produto; isso não permite duplicar a posição global de um produto.

Em corrida, a PK de estoque e a unicidade de movimento decidem o vencedor.
Somente conflitos desses dois nomes são reavaliados como cadastro/replay,
após rollback e em transação independente de leitura. Falhas de integridade
de outras constraints não são convertidas em sucesso. A tentativa perdedora
não deixa estoque órfão, movimento parcial ou chave reservada.

Não há limpeza automática de registros de idempotência. A V2 será executada
pelo Flyway no próximo arranque normal; nesta entrega é validada somente em
schemas temporários de PostgreSQL, sem alterar a base de uso da aplicação.

## Testes

- InventoryRegistrationHttpTests: autenticação/ADMIN, ator JWT, validação,
  limites, Location, erros de integração e ausência de falso sucesso.
- CatalogClientTests: HTTP real para servidor local de teste, compatibilidade
  do ID, estados comerciais, header Authorization ausente, falhas e timeout.
- RegisterStockUseCaseTests: ordem de consulta/escrita, replay sem Catalog,
  duplicidade, falha externa sem escrita e integridade não mascarada.
- InventoryRegistrationPersistenceTests: controller/caso de uso/transactions
  reais com PostgreSQL e Catalog simulado, inclusive commit, replay após mudança
  de saldo, conflito de payload, isolamento por ator, rollback e corridas.

Os testes simulam a futura mudança de saldo apenas no fixture; não demonstram
um endpoint de ajuste já disponível. As chamadas ao Catalog são verificadas
fora de transação ativa. Os schemas gerados por cada teste são removidos ao final.

```powershell
.\gradlew.bat test --tests '*InventoryRegistrationHttpTests' --tests '*CatalogClientTests' --tests '*RegisterStockUseCaseTests' --offline --no-daemon
```

Para PostgreSQL, usar as variáveis e o comando de suíte completa descritos em
[TESTING.md](TESTING.md). Não são necessários Keycloak/Catalog reais para os
testes de cadastro. O teste opt-in de token real da microtarefa 32 permanece
separado e não equivale a um cadastro real através do Keycloak.

Resultado da entrega: 120 testes aprovados, sem falhas ou ignorados, na suíte
completa com PostgreSQL e token de serviço real. Inclui atualização V1 -> V2
preservando estoque anterior sem backfill fictício de movimentos. Nenhum teste
foi desativado e nenhuma migration anterior foi alterada.

## Arquivos desta entrega

Caminhos relativos à raiz do monorepo; os links partem deste documento.

- [inventory-service/src/main/java/br/com/hadryan/shosphere/inventory/controller/InventoryController.java](src/main/java/br/com/hadryan/shosphere/inventory/controller/InventoryController.java)
- [inventory-service/src/main/java/br/com/hadryan/shosphere/inventory/client/CatalogClient.java](src/main/java/br/com/hadryan/shosphere/inventory/client/CatalogClient.java)
- [inventory-service/src/main/java/br/com/hadryan/shosphere/inventory/config/CatalogClientConfig.java](src/main/java/br/com/hadryan/shosphere/inventory/config/CatalogClientConfig.java)
- [inventory-service/src/main/java/br/com/hadryan/shosphere/inventory/mapper/request/RegisterStockRequest.java](src/main/java/br/com/hadryan/shosphere/inventory/mapper/request/RegisterStockRequest.java)
- [inventory-service/src/main/java/br/com/hadryan/shosphere/inventory/mapper/response/StockResponse.java](src/main/java/br/com/hadryan/shosphere/inventory/mapper/response/StockResponse.java)
- [inventory-service/src/main/java/br/com/hadryan/shosphere/inventory/model/InventoryMovement.java](src/main/java/br/com/hadryan/shosphere/inventory/model/InventoryMovement.java)
- [inventory-service/src/main/java/br/com/hadryan/shosphere/inventory/model/enums/InventoryMovementType.java](src/main/java/br/com/hadryan/shosphere/inventory/model/enums/InventoryMovementType.java)
- [inventory-service/src/main/java/br/com/hadryan/shosphere/inventory/repository/InventoryMovementRepository.java](src/main/java/br/com/hadryan/shosphere/inventory/repository/InventoryMovementRepository.java)
- [inventory-service/src/main/java/br/com/hadryan/shosphere/inventory/service/RegisterStockUseCase.java](src/main/java/br/com/hadryan/shosphere/inventory/service/RegisterStockUseCase.java)
- [inventory-service/src/main/java/br/com/hadryan/shosphere/inventory/service/impl/RegisterStockUseCaseImpl.java](src/main/java/br/com/hadryan/shosphere/inventory/service/impl/RegisterStockUseCaseImpl.java)
- [inventory-service/src/main/java/br/com/hadryan/shosphere/inventory/service/impl/RegisterStockTransaction.java](src/main/java/br/com/hadryan/shosphere/inventory/service/impl/RegisterStockTransaction.java)
- [inventory-service/src/main/java/br/com/hadryan/shosphere/inventory/service/impl/StockRegistrationIdempotency.java](src/main/java/br/com/hadryan/shosphere/inventory/service/impl/StockRegistrationIdempotency.java)
- [inventory-service/src/main/resources/application-dev.yaml](src/main/resources/application-dev.yaml)
- [inventory-service/src/main/resources/db/migration/V2__create_inventory_movements.sql](src/main/resources/db/migration/V2__create_inventory_movements.sql)
- [inventory-service/src/test/java/br/com/hadryan/shosphere/inventory/InventoryServiceApplicationTests.java](src/test/java/br/com/hadryan/shosphere/inventory/InventoryServiceApplicationTests.java)
- [inventory-service/src/test/java/br/com/hadryan/shosphere/inventory/controller/InventoryRegistrationHttpTests.java](src/test/java/br/com/hadryan/shosphere/inventory/controller/InventoryRegistrationHttpTests.java)
- [inventory-service/src/test/java/br/com/hadryan/shosphere/inventory/client/CatalogClientTests.java](src/test/java/br/com/hadryan/shosphere/inventory/client/CatalogClientTests.java)
- [inventory-service/src/test/java/br/com/hadryan/shosphere/inventory/service/impl/RegisterStockUseCaseTests.java](src/test/java/br/com/hadryan/shosphere/inventory/service/impl/RegisterStockUseCaseTests.java)
- [inventory-service/src/test/java/br/com/hadryan/shosphere/inventory/persistence/InventoryRegistrationPersistenceTests.java](src/test/java/br/com/hadryan/shosphere/inventory/persistence/InventoryRegistrationPersistenceTests.java)
- [inventory-service/src/test/java/br/com/hadryan/shosphere/inventory/persistence/InventoryStartupPersistenceTests.java](src/test/java/br/com/hadryan/shosphere/inventory/persistence/InventoryStartupPersistenceTests.java)
- [inventory-service/REGISTRATION.md](REGISTRATION.md)
- [inventory-service/TESTING.md](TESTING.md)
- [inventory-service/SECURITY.md](SECURITY.md)
- [inventory-service/PLAN.md](PLAN.md)
- [BACKLOG.md](../BACKLOG.md)
