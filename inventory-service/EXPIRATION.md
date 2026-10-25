# Expiração e reconciliação — microtarefa 39

Implementa o job aprovado em `../contracts/inventory-v1-proposal.md`.
Reservas ACTIVE com expiresAt <= agora passam a EXPIRED e devolvem somente
reserved, sem alterar onHand. CONFIRMED/RELEASED/EXPIRED e ACTIVE ainda válida
não são modificadas. GET e replay permanecem sem efeitos de expiração.

## Configuração

| Propriedade | Variável de ambiente | Padrão |
|---|---|---|
| inventory.reservations.expiration.enabled | SHOPSPHERE_INVENTORY_EXPIRATION_ENABLED | true |
| inventory.reservations.expiration.delay | SHOPSPHERE_INVENTORY_EXPIRATION_DELAY | PT60S |
| inventory.reservations.expiration.batch-size | SHOPSPHERE_INVENTORY_EXPIRATION_BATCH_SIZE | 50 |

O prazo da reserva continua em inventory.reservations.ttl, padrão PT15M.
O intervalo do job usa duração ISO-8601, com mínimo de um milissegundo;
batch-size deve ser positivo. Intervalo ou lote inválidos impedem o arranque.

O agendamento é habilitado no processo servlet do Inventory. A primeira
execução ocorre após inicialização do contexto; as próximas usam fixedDelay,
aguardando o intervalo configurado depois do término da varredura anterior.
Pode ser desabilitado explicitamente pela propriedade enabled.

Testes de persistência não web executam o job diretamente, sem scheduler em
segundo plano. O perfil test desabilita o agendamento para isolar testes HTTP
e de contexto. Um teste específico verifica agendamento real habilitado.

## Varredura, retomada e concorrência

Cada varredura fixa um cutoff e consulta IDs/prazos de ACTIVE vencidas em
lotes limitados, ordenados por expiresAt e orderId. A continuação usa esse
cursor, sem offset: retirar reservas já processadas não faz pular outras.
A varredura percorre todos os lotes elegíveis até o cutoff; o tamanho do lote
limita cada consulta, não o total de reservas processadas na execução.

Reservas com falha também ficam para trás do cursor, permitindo processar
as próximas, inclusive quando as primeiras páginas contêm falhas. O cursor
reinicia em cada varredura, portanto falhas e locks ocupados são revisitados.
Após reinício, o job lê o estado persistido e recupera as vencidas pendentes;
não depende de temporizadores ou de uma lista de reservas mantida em memória.

Cada reserva é processada por ExpireReservationUseCase em REQUIRES_NEW.
O worker seleciona a reserva ACTIVE vencida com FOR UPDATE SKIP LOCKED.
Se outro processo a mantém bloqueada, deixa-a para uma próxima varredura.
Depois bloqueia todas as posições em UUID.compareTo, como criação e
transições, revalida a expiração e devolve cada quantidade reservada.
Estado e contadores são confirmados na mesma transação.

Falha em qualquer item desfaz a reserva inteira; outras reservas têm
transações independentes. O job registra apenas orderId e classe da exceção,
sem mensagens internas, SQL, JWTs ou credenciais. Uma seleção de lote que
falhe interrompe aquela varredura; o scheduler poderá executá-la novamente
no próximo intervalo. Não há retry em loop para um item com falha.

Vários workers/instâncias podem observar os mesmos candidatos. Lock, estado
e prazo são revalidados no banco: expiração, liberação e confirmação não
podem devolver ou consumir a mesma reserva duas vezes. A confirmação vencida
continua retornando 409 sem consumir. Reservas CONFIRMED não são expiradas,
mesmo após o prazo original.

## Migração e limites

V4 adiciona apenas idx_inventory_reservations_expiration em
(status, expires_at, order_id), para apoiar filtro, ordenação e cursor.
V1–V3 permanecem intactas. Não há backfill, exclusão ou alteração dos registros
existentes. O índice será criado pelo Flyway no arranque da aplicação;
esta entrega o aplicou somente em schemas temporários próprios dos testes.

Não há novo endpoint ou alteração de DTOs, permissões, Keycloak, Gateway,
Order/Cart, broker ou dependências. Não houve deploy. A reconciliação trata
reservas ACTIVE vencidas persistidas; não inventa correções de saldo para
dados inconsistentes. Esses casos fazem rollback, são registrados e voltam
a ser tentados em varreduras posteriores. A reserva pode continuar ACTIVE
e reter saldo até o job ou liberação conseguir processá-la.

## Arquivos alterados

Relativos a `src/main/java/br/com/hadryan/shosphere/inventory/`:

- `model/InventoryReservation.java`
- `repository/InventoryReservationRepository.java`
- `service/ExpireReservationUseCase.java` (novo)
- `service/impl/ExpireReservationUseCaseImpl.java` (novo)
- `service/ReservationExpirationJob.java` (novo)
- `config/ReservationExpirationConfig.java` (novo)

Migration nova: `src/main/resources/db/migration/V4__index_inventory_reservation_expiration.sql`.
Configuração: `src/main/resources/application.properties`.

Relativos a `src/test/java/br/com/hadryan/shosphere/inventory/`:

- `service/ReservationExpirationJobTests.java` (novo)
- `config/ReservationExpirationConfigTests.java` (novo)
- `persistence/InventoryReservationExpirationTests.java` (novo)
- `model/InventoryReservationTests.java`
- `persistence/InventoryStartupPersistenceTests.java` (versões Flyway)
- `persistence/InventoryReservationPersistenceTests.java` (versão atual)

Configuração de teste: `src/test/resources/application-test.yaml`.
Documentação: `EXPIRATION.md`, `RESERVATIONS.md`, `RESERVATION_TRANSITIONS.md`,
`SECURITY.md`, `TESTING.md`, `PLAN.md` e `../BACKLOG.md`.

## Validação

Testes cobrem prazo e estados, paginação/cursor, validação da configuração,
agendamento habilitado/desabilitado, retomada após reinício, falhas sem bloquear
as próximas páginas, rollback, retry em outra varredura, SKIP LOCKED, múltiplos
workers e disputa com confirmação/liberação. Upgrade V3→V4 verifica o índice
e uma reserva vencida preservada até seu processamento. Comandos e resultados
em [TESTING.md](TESTING.md).
