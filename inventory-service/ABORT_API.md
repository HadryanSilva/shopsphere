# API de aborto durável — microtarefa 51

Implementa a exposição HTTP aprovada no [contrato Order–Inventory](../contracts/order-inventory-proposal.md),
reutilizando o caso de uso e a coordenação da [microtarefa 50](ABORT_PERSISTENCE.md).

## Contrato interno

`POST /internal/api/v1/inventory/reservations/{orderId}/abort`

O body usa o mesmo contrato de itens do PUT de reserva: 1–100 produtos UUID
distintos, com quantidade inteira positiva. Exemplo:

```json
{"items":[{"productId":"00000000-0000-0000-0000-000000000001","quantity":1}]}
```

Sucesso inicial e replay equivalente retornam 200, sem Location:

```json
{"orderId":"00000000-0000-0000-0000-000000000002","status":"ABORTED","reservation":null}
```

O campo reservation está sempre presente. É null quando nunca houve reserva;
quando existente, contém o ReservationResponse atual em RELEASED ou EXPIRED.
ABORTED é o resultado da operação, sem acrescentar estado ao enum de reservas.

- Sem reserva: grava impedimento durável mesmo sem posição de estoque.
- ACTIVE equivalente: libera o reservado atomicamente; o prazo decide RELEASED/EXPIRED.
- RELEASED/EXPIRED equivalente: confirma o impedimento sem nova devolução.
- CONFIRMED ou intenção de itens divergente: 409, sem efeitos parciais.
- Vínculo com outro cliente: 404. Payload/UUID inválido: 400.
- PUT posterior à barreira: 409. GET e release continuam 404 se nunca houve reserva.

Erros preservam Problem Details. A identidade vem de azp no JWT, sem aceitar
identidade enviada pelo body. Assinatura, issuer, validade e audiência seguem a
configuração existente; é obrigatório ter realm role INVENTORY_SERVICE e
azp shopsphere-order. ADMIN ou scopes isolados não autorizam a operação.
Anónimo recebe 401; identidade/permissão inadequada, 403.

## Limites e validação

O Gateway não possui rota para o aborto e nega o caminho interno inclusive
para ADMIN e para a identidade de serviço autorizada diretamente no Inventory.
Não há migration, dependência, chamada remota ou integração com Order nesta
entrega. V1–V5 permanecem intactas; o cliente Order pertence às etapas seguintes.

Testes cobrem JSON com null explícito, replay, estados terminais, validação,
Problem Details, JWT e isolamento do Gateway. Um teste HTTP com PostgreSQL
real verifica a barreira, o PUT bloqueado e a liberação de reserva existente.
Comandos, resultados e limitações em [TESTING.md](TESTING.md).
