# Ajustes de estoque — microtarefa 34

Implementa o contrato aprovado em `../contracts/inventory-v1-proposal.md`.

- `POST /api/v1/inventory/items/{productId}/adjustments`: ADMIN, JWT com
  `sub` válido e `Idempotency-Key` UUID. Corpo: `delta` inteiro diferente de
  zero e `reason` não branco de até 500 caracteres, preservado sem trim.
- Retorna 201 com `id`, `productId`, `delta`, `reason`, `onHandAfter`,
  `reservedAfter`, `createdAt` e Location para o movimento.
- `GET /api/v1/inventory/items/{productId}/adjustments/{id}`: ADMIN, retorna
  200 com o movimento; 404 para movimento ausente, de outro produto ou INITIAL.
- Item não cadastrado: 404. Saldo final negativo, abaixo das reservas ou
  acima de Integer.MAX_VALUE: 409. Payload inválido: 400.

A atualização da posição e o movimento ADJUSTMENT são atômicos. O bloqueio
pessimista da posição serializa ajustes do mesmo produto; a chave é
reverificada sob o bloqueio antes de validar o saldo. A constraint existente
de ator/tipo/chave resolve disputas entre produtos, com rollback integral
antes de consultar o vencedor. Não há chamada ao Catalog neste fluxo.

Repetições do mesmo ator/tipo/chave e produto/delta/motivo retornam o mesmo
movimento e snapshot original, inclusive após outros ajustes. Divergências
retornam 409. Falhas não consomem a chave. INITIAL e ADJUSTMENT têm espaços de
chaves independentes. O timestamp do ajuste usa microssegundos para preservar
a igualdade entre a primeira resposta e a leitura posterior no PostgreSQL.

Não há nova migration: V2 já define ADJUSTMENT. V1/V2 permanecem intactas.
Não há implementação de reservas, consultas de saldo/batch, Gateway ou ligação
ao Order nesta entrega. Reservas existentes são preservadas pelo ajuste.

## Arquivos desta entrega

Todos os caminhos Java abaixo são relativos a
`src/main/java/br/com/hadryan/shosphere/inventory/`:

- `controller/InventoryController.java`
- `exception/ApiExceptionHandler.java`
- `exception/StockAdjustmentConflictException.java`
- `mapper/request/AdjustStockRequest.java`
- `mapper/response/AdjustmentResponse.java`
- `model/InventoryStock.java`
- `model/InventoryMovement.java`
- `repository/InventoryStockRepository.java`
- `repository/InventoryMovementRepository.java`
- `service/AdjustStockUseCase.java`
- `service/GetStockAdjustmentUseCase.java`
- `service/impl/AdjustStockUseCaseImpl.java`
- `service/impl/AdjustStockTransaction.java`
- `service/impl/StockAdjustmentIdempotency.java`
- `service/impl/GetStockAdjustmentUseCaseImpl.java`

Testes, relativos a `src/test/java/br/com/hadryan/shosphere/inventory/`:

- `controller/InventoryAdjustmentHttpTests.java`
- `controller/InventoryRegistrationHttpTests.java` (mocks dos novos casos de uso)
- `model/InventoryStockTests.java`
- `persistence/InventoryAdjustmentPersistenceTests.java`

Documentação: `ADJUSTMENTS.md`, `TESTING.md`, `PLAN.md` e `../BACKLOG.md`.

## Validação

Testes HTTP validam criação/Location/consulta, identidade JWT, permissões,
payloads inválidos e conflito. Testes de domínio validam reservas e limites.
Testes PostgreSQL usam schemas temporários próprios e verificam snapshots,
replay, divergência de intenção, filtros da consulta, rollback, chave não
consumida por falha e concorrência pela última unidade/mesma chave.

Comando completo: `$env:SHOPSPHERE_TEST_POSTGRES_URL =
'jdbc:postgresql://localhost:5432/inventory_db'; .\gradlew.bat test --offline
--no-daemon --rerun-tasks`. O teste opcional de token real requer também
SHOPSPHERE_TEST_INVENTORY_SERVICE_TOKEN; os demais testes de segurança usam
JWTs de teste. Resultados desta execução registrados em TESTING.md.
