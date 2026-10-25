# Confirmar e liberar reservas — microtarefa 38

Implementa os POSTs aprovados em `../contracts/inventory-v1-proposal.md`,
sem alterar a política de acesso existente. Ambos exigem realm role
INVENTORY_SERVICE e azp shopsphere-order; a identidade vem do JWT validado.

- `POST /internal/api/v1/inventory/reservations/{orderId}/confirm`, sem body:
  200 com a reserva CONFIRMED. ACTIVE exige prazo ainda válido e consome a
  quantidade de cada item de onHand e reserved. CONFIRMED retorna 200 sem
  repetir o consumo, mesmo que o prazo original já tenha passado.
- `POST /internal/api/v1/inventory/reservations/{orderId}/release`, sem body:
  200 com a reserva. ACTIVE antes do prazo torna-se RELEASED; no prazo ou
  depois dele torna-se EXPIRED. Ambos devolvem apenas reserved, sem aumentar
  onHand. RELEASED/EXPIRED retornam 200 sem nova devolução.
- Confirmar ACTIVE vencida, RELEASED ou EXPIRED retorna 409. A confirmação
  vencida não muda estado ou saldo; liberação/job processará a expiração.
  Liberar CONFIRMED retorna 409, sem devolver estoque já consumido.
- Reserva inexistente ou de outro cliente retorna 404. Identificador
  inválido retorna 400; ausência de autenticação retorna 401 e permissão
  insuficiente retorna 403. Erros seguem Problem Details existente.

As respostas mantêm `{orderId,status,items,expiresAt,createdAt,updatedAt}`.
Itens, prazo e data de criação não mudam. Repetições e conflitos não atualizam
timestamps, versões ou contadores. PUT posterior equivalente continua
retornando o estado terminal, sem reativar a reserva.

## Atomicidade e bloqueios

TransitionReservationUseCaseImpl executa cada operação em REQUIRES_NEW.
Bloqueia primeiro a reserva, confere o cliente e, para ACTIVE, bloqueia todas
as posições na ordem natural UUID.compareTo, igual à criação de reservas.
A comparação do prazo ocorre depois de obter os locks de estoque. Uma
confirmação que aguarda até depois do prazo não pode consumir saldo.

As mudanças de estado e de todos os contadores são confirmadas na mesma
transação local. Falha em um item desfaz os efeitos nos itens anteriores e
no estado. Quantidades consumidas/liberadas devem ser positivas e não podem
ultrapassar o reservado. Inconsistência interna de contadores interrompe a
operação e faz rollback, sem fabricar saldo ou devolver detalhes internos.

Estados terminais são tratados sob o lock da reserva, antes dos locks de
estoque. Assim, várias confirmações ou liberações retornam o mesmo resultado;
uma disputa entre confirmar e liberar não pode concluir ambos os efeitos.
Não há chamadas HTTP externas, Catalog, mensageria ou retries automáticos.

## Escopo e implantação

Não há migration ou dependência nova; V1–V3 ficam intactas. Não há mudança de
permissões, Keycloak, Gateway, Order ou Cart. O job de expiração/reconciliação
foi implementado na microtarefa 39; detalhes em [EXPIRATION.md](EXPIRATION.md).
A liberação explícita também processa ACTIVE vencida; GET e replay não
expiram automaticamente. Não foi feito deploy.

## Arquivos alterados

Relativos a `src/main/java/br/com/hadryan/shosphere/inventory/`:

- `controller/InventoryReservationController.java`
- `model/InventoryReservation.java`
- `model/InventoryStock.java`
- `exception/ApiExceptionHandler.java`
- `exception/ReservationStateConflictException.java` (novo)
- `service/TransitionReservationUseCase.java` (novo)
- `service/impl/TransitionReservationUseCaseImpl.java` (novo)

Relativos a `src/test/java/br/com/hadryan/shosphere/inventory/`:

- `controller/InventoryReservationTransitionHttpTests.java` (novo)
- `controller/InventoryReservationHttpTests.java` (mock do novo caso de uso)
- `model/InventoryReservationTests.java`
- `model/InventoryStockTests.java`
- `persistence/InventoryReservationTransitionTests.java` (novo)

Documentação: `RESERVATION_TRANSITIONS.md`, `RESERVATIONS.md`, `SECURITY.md`,
`TESTING.md`, `PLAN.md` e `../BACKLOG.md`.

## Validação

Testes de domínio cobrem matriz de estados, instante exato de expiração,
limites inteiros e rejeição de contadores inconsistentes. HTTP cobre os novos
POSTs, JWT/role/azp, códigos de resposta e erros. Testes PostgreSQL cobrem
consumo/devolução únicos, preservação de outras reservas, versões/timestamps,
isolamento por cliente, rollback em item posterior, prazo após espera por
lock, concorrência entre transições, reservas sobrepostas, nova reserva em
disputa com liberação e lotes de 100 itens.

Os testes usam somente schemas temporários próprios. Fixtures SQL de prazo
e inconsistência de contador são restritas a esses schemas. Comandos e
resultados em [TESTING.md](TESTING.md).
