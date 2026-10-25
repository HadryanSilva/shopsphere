# Criação e consulta de reservas — microtarefa 37

Implementa PUT e GET do contrato aprovado em
`../contracts/inventory-v1-proposal.md`, com a política de segurança existente:
realm role INVENTORY_SERVICE **e** azp shopsphere-order. ADMIN isoladamente
não concede acesso. A identidade vem do JWT validado, nunca do body.

## Contrato

`PUT /internal/api/v1/inventory/reservations/{orderId}` aceita
`{items:[{productId,quantity}]}`: de 1 a 100 produtos UUID distintos, com
quantidade inteira positiva até Integer.MAX_VALUE. Duplicatas são rejeitadas,
não somadas. O orderId é a identidade e a chave de idempotência da reserva.

A criação retorna 201; repetição equivalente retorna 200. Ambas incluem
Location do recurso e `{orderId,status,items,expiresAt,createdAt,updatedAt}`.
A ordem do payload não altera a intenção. Repetições não renovam prazo,
não alteram saldo nem reativam estados terminais. Payload divergente para
o mesmo pedido retorna 409. Falhas não consomem o orderId.

`GET /internal/api/v1/inventory/reservations/{orderId}` retorna 200 com o
registro atual, sem alterar estado. Reserva inexistente ou pertencente a
outro cliente retorna 404, tanto para consulta quanto para repetição do PUT.
Posição inexistente retorna 404; saldo disponível insuficiente retorna 409;
payload inválido retorna 400. Ausência de autenticação retorna 401 e
permissões insuficientes retornam 403. Erros usam Problem Details existente.

## Transação e concorrência

O caso de uso executa em transação REQUIRES_NEW. Primeiro insere somente o
cabeçalho com `ON CONFLICT (order_id) DO NOTHING`, direcionado à PK existente.
Uma corrida pela mesma identidade aguarda o commit/rollback do concorrente.
Não se captura genericamente erro de integridade como repetição; não há
transação marcada para rollback sendo reutilizada para ler um vencedor.

Depois bloqueia a reserva com PESSIMISTIC_WRITE, verifica o proprietário e,
se ela já existia, compara itens/quantidades ordenados. Somente uma criação
bloqueia todas as posições, na ordem natural de UUID usada por Java UUID.compareTo.
Essa ordem deve ser mantida pelas próximas operações com vários produtos.

O cabeçalho é estabelecido **antes** dos bloqueios de estoque, mas os itens
com FKs para posições são inseridos **depois** desses bloqueios. Isso evita
adquirir locks de FK antes da ordem de locks definida. initializeItems permite
preencher apenas um cabeçalho ACTIVE ainda vazio, sem editar uma intenção
estabelecida. Esse cabeçalho provisório não é commitado sem os itens.

Com todos os locks obtidos, reserve(quantity) aumenta somente reserved quando
quantity <= available. onHand permanece intacto. A comparação garante que a
soma não ultrapasse onHand/Integer.MAX_VALUE. Saldo, itens e cabeçalho são
gravados na mesma transação; falha em qualquer item desfaz todos os efeitos.
O ajuste administrativo usa o mesmo lock da posição e respeita as reservas.

O SQL nativo usa o placeholder de schema do Hibernate, validado com schemas
temporários próprios nos testes. Não há migration nova ou mudança em V1–V3,
novas dependências, chamadas ao Catalog, eventos ou acesso ao banco do Order.

## Prazo e escopo restante

`inventory.reservations.ttl` usa duração ISO-8601, padrão `PT15M`, configurável
por `SHOPSPHERE_INVENTORY_RESERVATION_TTL`. Valores menores que um microssegundo
são rejeitados no arranque, assim como formato inválido. O prazo é definido
somente na criação e não é escolhido pelo cliente. Datas usam microssegundos.

Liberação/confirmação foram implementadas na microtarefa 38; detalhes em
[RESERVATION_TRANSITIONS.md](RESERVATION_TRANSITIONS.md). O processamento
automático de expiração foi implementado na 39, conforme [EXPIRATION.md](EXPIRATION.md).
Uma reserva vencida pode continuar ACTIVE e manter
saldo reservado; GET e replay não expiram nem renovam a reserva. Não há ligação
automática ao checkout, alteração do Order/Cart, mudança de Keycloak ou rota de
Gateway. O Inventory valida JWT e permissões por si próprio.

## Arquivos alterados

Relativos a `src/main/java/br/com/hadryan/shosphere/inventory/`:

- `controller/InventoryReservationController.java` (novo)
- `mapper/request/ReserveStockRequest.java` (novo)
- `mapper/response/ReservationResponse.java` (novo)
- `service/ReserveStockUseCase.java` (novo)
- `service/GetReservationUseCase.java` (novo)
- `service/impl/ReserveStockUseCaseImpl.java` (novo)
- `service/impl/GetReservationUseCaseImpl.java` (novo)
- `exception/StockReservationConflictException.java` (novo)
- `exception/ApiExceptionHandler.java`
- `model/InventoryStock.java`
- `model/InventoryReservation.java`
- `repository/InventoryReservationRepository.java`

Configuração: `src/main/resources/application.properties`.

Relativos a `src/test/java/br/com/hadryan/shosphere/inventory/`:

- `controller/InventoryReservationHttpTests.java` (novo)
- `service/impl/ReserveStockUseCaseTests.java` (novo)
- `persistence/InventoryReservationCreationTests.java` (novo)
- `model/InventoryStockTests.java`
- `model/InventoryReservationTests.java`
- `InventoryServiceApplicationTests.java`

Documentação: `RESERVATIONS.md`, `SECURITY.md`, `TESTING.md`, `PLAN.md` e
`../BACKLOG.md`.

## Validação

Testes HTTP verificam 201/200/Location/GET, identidade, permissões e payloads
inválidos. Testes de domínio verificam limites, duplicatas e TTL inválido.
Testes PostgreSQL verificam atomicidade, replay reordenado, divergências,
isolamento por cliente, ausência/insuficiência, rollback e reuso do orderId,
prazo configurável, replay de estados terminais/vencidos, 100 itens,
concorrência do mesmo pedido, intenções diferentes, última unidade, reservas
sobrepostas em ordem inversa e disputa entre ajuste e reserva.

Estados terminais nos testes de criação da microtarefa 37 usam fixtures SQL;
transições reais foram implementadas e testadas na microtarefa 38, conforme
[RESERVATION_TRANSITIONS.md](RESERVATION_TRANSITIONS.md). Testes de banco usam somente schemas temporários;
Catalog é simulado e não é chamado na criação. Comandos e resultados em
[TESTING.md](TESTING.md).
