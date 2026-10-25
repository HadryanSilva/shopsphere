# Consultas de estoque — microtarefa 35

Implementa os GETs administrativos do contrato aprovado em
`../contracts/inventory-v1-proposal.md`, usando a política ADMIN existente.

- `GET /api/v1/inventory/items/{productId}`: 200 com
  `{productId,onHand,reserved,available,version,updatedAt}`; item não cadastrado
  retorna 404. Estoque zero retorna uma posição normal.
- `GET /api/v1/inventory/items/batch?productIds=UUID,UUID`: 200 com uma lista
  de posições, sem garantia de ordem. Aceita também parâmetros productIds
  repetidos. Exige 1 a 100 UUIDs distintos; duplicatas e entradas inválidas
  retornam 400. Itens não cadastrados são omitidos; nenhum encontrado retorna `[]`.
- Ausência de autenticação retorna 401; permissões insuficientes retornam 403.
  O cliente de reservas não recebe acesso a esses caminhos administrativos.

As consultas reutilizam StockResponse e o InventoryStockRepository. O batch
usa findAllById, evitando uma consulta por produto. Os DTOs são montados dentro
de transação somente de leitura; available é calculado como onHand - reserved.
Não há chamadas ao Catalog, locks de escrita ou criação de movimentos.

O saldo representa o estado observado na consulta. Pode mudar logo depois;
consultar disponibilidade não reserva nem garante estoque para checkout.

Não há novas migrations, dependências, permissões, configurações de Keycloak
ou rotas de Gateway. Os fluxos de cadastro e ajuste permanecem preservados.
Reservas e integração com Order/Cart continuam nas próximas entregas.

## Arquivos alterados

Arquivos novos de produção, relativos a
`src/main/java/br/com/hadryan/shosphere/inventory/`:

- `controller/InventoryQueryController.java`
- `service/GetStockUseCase.java`
- `service/impl/GetStockUseCaseImpl.java`

Testes novos, relativos a `src/test/java/br/com/hadryan/shosphere/inventory/`:

- `controller/InventoryQueryHttpTests.java`
- `persistence/InventoryQueryPersistenceTests.java`

Documentação: `QUERIES.md`, `TESTING.md`, `SECURITY.md`, `PLAN.md` e `../BACKLOG.md`.

## Validação

Testes HTTP exercitam controller, caso de uso, validação e segurança reais,
com repositório simulado. Cobrem UUIDs, duplicatas, limites, formatos de
parâmetro, saldo zero, item ausente e permissões. Testes PostgreSQL validam
saldo reservado/limite inteiro, lote de 100 posições, omissão de ausentes e
ausência de alterações em versão, timestamp e movimentos ou chamadas Catalog.
Usam somente schemas temporários próprios. Comandos e resultados em TESTING.md.
