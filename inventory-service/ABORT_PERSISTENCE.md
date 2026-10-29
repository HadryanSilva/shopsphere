# Coordenação e aborto durável de reservas — microtarefa 50

Implementa a persistência e o caso de uso local aprovados no
[contrato Order–Inventory](../contracts/order-inventory-proposal.md).
O endpoint /abort, seu DTO HTTP e a allowlist ficam para a microtarefa 51.
Não há chamada ao Inventory a partir do Order nesta entrega.

## Modelo e migration

V5__coordinate_reservation_abort.sql cria inventory_reservation_coordination
e inventory_reservation_coordination_items. A raiz usa order_id como PK,
service_client_id, created_at, aborted_at nullable e versão otimista. Os itens
guardam product_id/quantity da intenção imutável, com PK por pedido/produto,
quantidade positiva e FK somente para a raiz de coordenação.

Não existe FK desses itens para posições ou para Order: o aborto precisa
funcionar antes de qualquer reserva, inclusive quando falta cadastro de estoque.
Os checks rejeitam cliente vazio, versão negativa e aborto anterior à criação.
O modelo exige 1–100 produtos distintos e compara quantidades sem depender da
ordem do payload. Os impedimentos não são excluídos ou expirados automaticamente.

Não foi acrescentado estado ao enum InventoryReservationStatus. aborted_at
marca a decisão na coordenação; a reserva, se existir, continua usando
ACTIVE/CONFIRMED/RELEASED/EXPIRED. O resultado Java do aborto contém orderId e
reservation nullable; a representação HTTP ABORTED será acrescentada na 51.

A migration é aditiva e não modifica V1–V4, saldos, reservas ou snapshots.
Não fabrica coordenações para dados existentes. Ao operar uma reserva que já
existe sem coordenação, criação/replay ou aborto estabelece a coordenação e
confere também a identidade e os itens da reserva sob lock. Divergências fazem
rollback da coordenação recém-criada.

## Transação e ordem de locks

ReservationCoordinationTransaction exige transação já aberta (MANDATORY).
Valida o payload antes de acessar o banco, insere a raiz com ON CONFLICT apenas
na PK e a bloqueia com PESSIMISTIC_WRITE. Quem perde uma corrida aguarda o
commit/rollback e relê a raiz vencedora. Não se trata qualquer erro de
integridade como replay, nem se reutiliza uma transação falhada.

ReserveStockUseCaseImpl e AbortReservationUseCaseImpl mantêm REQUIRES_NEW e
adotam a ordem: coordenação, reserva e posições por UUID.compareTo. A coleção
da coordenação não cria locks de FK sobre posições antes dessa ordem.
Confirmação/liberação/expiração existentes continuam começando pela reserva;
nenhuma delas passa a adquirir coordenação depois, evitando inversão de locks.

O PUT verifica cliente/itens e aborted_at antes de criar/repetir a reserva.
Aborto gravado rejeita PUT com 409, sem tocar estoque ou renovar o prazo.
Criação de reserva rejeitada por ausência/insuficiência continua revertendo
tudo, inclusive a nova coordenação: a falha não consome a intenção.

O aborto local:

- Sem reserva: grava o impedimento e retorna reservation=null, sem consultar
  posições nem criar uma reserva artificial.
- ACTIVE equivalente: bloqueia posições, decide RELEASED/EXPIRED pelo instante
  após os locks e devolve somente reserved. Impedimento, estado e saldos fazem
  commit na mesma transação; nenhum HTTP ocorre dentro dela.
- RELEASED/EXPIRED equivalente: confirma o impedimento sem nova devolução nem
  mudança de timestamps da reserva.
- CONFIRMED: conflito, sem devolver saldo físico/reservado e sem gravar aborto.
- Cliente divergente: 404; itens divergentes: 409. Não sobrescreve intenção.

Repetições equivalentes do aborto preservam aborted_at e versão. O snapshot
também permanece estável após reinício. Aborto que vence impede PUT atrasado;
PUT que vence é liberado pelo aborto seguinte. Uma barreira ainda não commitada
faz o PUT aguardar; se houver rollback da barreira, o PUT pode criar a reserva.
GET e release continuam retornando 404 quando a reserva nunca existiu; isso
não apaga nem substitui a prova durável de aborto.

## Arquivos principais

Relativos a src/main/java/br/com/hadryan/shosphere/inventory:

- model/InventoryReservationCoordination.java.
- repository/InventoryReservationCoordinationRepository.java.
- service/impl/ReservationCoordinationTransaction.java.
- service/AbortReservationUseCase.java e service/impl/AbortReservationUseCaseImpl.java.
- service/impl/ReserveStockUseCaseImpl.java.

Migration: src/main/resources/db/migration/V5__coordinate_reservation_abort.sql.
Testes novos: InventoryReservationCoordinationTests e InventoryReservationAbortTests.
Os testes de arranque incluem V5/as duas tabelas; o contexto sem datasource
simula o novo repositório, sem desativar componentes ou validações.

Resultados e comandos em [TESTING.md](TESTING.md). Flyway/JPA/PostgreSQL foram
exercitados somente em schemas temporários próprios dos testes. Não houve
migration em schema de uso normal, novo endpoint/DTO HTTP, alteração de
segurança/Keycloak, dependência, ligação Order/Cart, Gateway ou mensageria.
