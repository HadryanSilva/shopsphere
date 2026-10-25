# Persistência de reservas — microtarefa 36

Implementa a base de persistência da proposta aprovada em
`../contracts/inventory-v1-proposal.md`. Não implementa ainda o fluxo HTTP de
reservar, confirmar, liberar ou expirar, nem altera os contadores de estoque.

## Modelo e integridade

- `InventoryReservation`: orderId UUID atribuído pela aplicação e chave primária;
  serviceClientId obrigatório, não branco, até 255 caracteres; status
  ACTIVE/CONFIRMED/RELEASED/EXPIRED; expiresAt; versão otimista Long;
  createdAt/updatedAt em UTC com precisão de microssegundos.
- Novas instâncias começam ACTIVE. A versão começa null para o Spring Data
  inserir um novo UUID atribuído, evitando merge sobre uma reserva existente.
- `InventoryReservationItem`: valor embutido com productId UUID e quantity
  inteira positiva, até Integer.MAX_VALUE. A reserva exige 1–100 produtos
  distintos, rejeita duplicatas e copia a lista. Não há setters públicos ou
  edição da coleção; a intenção original não pode ser alterada por esses métodos.
- Itens usam `@ElementCollection` sem ordem persistida. A chave no banco é
  `(order_id, product_id)`, sem identificador artificial. As FKs apontam apenas
  para a reserva e a posição de estoque do próprio Inventory.
- `findByOrderIdAndServiceClientId` filtra pela identidade proprietária e
  carrega os itens com EntityGraph. Um cliente diferente recebe Optional vazio.
  Não existe novo endpoint nesta entrega.

A V3 cria `inventory_reservations` e `inventory_reservation_items`, com PKs,
FKs, NOT NULL e checks nomeados para cliente não branco, estados permitidos,
prazo posterior à criação e quantidade positiva. A largura INTEGER rejeita
overflow. O prazo é validado antes de persistir; expiresAt não é renovado por
atualizações da entidade. V1/V2 permanecem intactas. Não há FK para Order ou
Catalog, backfill, mudança de dados existentes ou nova dependência.

O limite de 1–100 itens é validado no domínio; não há trigger para contagem
entre linhas. Os checks não implementam a matriz de transições de estado ou
validam a soma de todas as reservas contra o contador reserved. Essas operações
exigem os casos de uso transacionais das microtarefas 37–39. O TTL configurável
será aplicado pela criação de reserva; esta camada apenas recebe o prazo.

A coleção não tem mutações públicas. A proteção de versão foi verificada para
alterações do cabeçalho, sem presumir que alterações isoladas de filhos sejam
protegidas automaticamente. A escrita concorrente dos fluxos futuros ainda
precisa bloquear reserva e posições na ordem aprovada. As regras de transição,
efeitos no saldo, idempotência e expiração serão implementadas nas etapas
37–39, sem expor mudanças isoladas de estado antes da atualização atômica do saldo.

## Validação e implantação

Testes verificam limites e imutabilidade do modelo; persistência/reinício com
Hibernate validate; isolamento por cliente; todos os estados aprovados; versão
otimista; rejeição de substituição de orderId; rollback quando falta posição;
100 itens; constraints SQL e upgrade V2→V3 preservando estoque e movimentos.
Mudanças de status nos testes usam reflexão/SQL apenas como fixtures para
verificar o mapeamento; não representam transições de negócio disponíveis.

A V3 foi aplicada somente em schemas temporários próprios dos testes locais.
Ainda precisará ser aplicada pelo Flyway no arranque do ambiente da aplicação.
Não foi feita implantação, alteração de Keycloak ou alteração de outros serviços.
Comandos e resultados em [TESTING.md](TESTING.md).

## Arquivos desta entrega

Novos arquivos de produção, relativos a
`src/main/java/br/com/hadryan/shosphere/inventory/`:

- `model/InventoryReservation.java`
- `model/InventoryReservationItem.java`
- `model/enums/InventoryReservationStatus.java`
- `repository/InventoryReservationRepository.java`

Migration nova: `src/main/resources/db/migration/V3__create_inventory_reservations.sql`.

Testes, relativos a `src/test/java/br/com/hadryan/shosphere/inventory/`:

- `model/InventoryReservationTests.java` (novo)
- `persistence/InventoryReservationPersistenceTests.java` (novo)
- `persistence/InventoryStartupPersistenceTests.java` (inventário de migrations/tabelas)

Documentação: `RESERVATION_PERSISTENCE.md`, `TESTING.md`, `PLAN.md` e `../BACKLOG.md`.
