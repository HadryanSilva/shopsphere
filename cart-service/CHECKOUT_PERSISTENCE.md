# Preparação local do checkout — microtarefa 43

Implementação do núcleo de persistência aprovado na
[microtarefa 42](../contracts/cart-checkout-proposal.md). Ainda não há endpoints
internos, provisionamento Keycloak ou chamadas Order → Cart para este protocolo.
O checkout público existente do Order não usa esta preparação nesta entrega.
Posteriormente, a etapa 44 expôs o protocolo HTTP e a identidade de serviço,
descritos em [CHECKOUT_API.md](CHECKOUT_API.md); a descrição abaixo registra o
escopo da entrega 43.

## Comportamento

CartCheckoutService prepara, consulta, conclui e aborta uma intenção local.
O chamador fornece checkoutId, customerId e identidade de serviço; a extração
dessa identidade de um JWT validado pertence à microtarefa 44. O serviço local
verifica o vínculo persistido, mas não substitui autenticação/autorização HTTP.

A preparação bloqueia o ACTIVE, valida que não está vazio nem preparado por
outra intenção e copia todos os productId/quantity. O snapshot não consulta
Catalog, não contém preços e não tem limite adicional de itens. A resposta
local é imutável, ordenada por productId, e informa se houve criação ou replay.
Timestamps usam microssegundos, preservando igualdade após leitura PostgreSQL.

PREPARED mantém o carrinho ACTIVE para leitura. Adição com/sem cartId, PATCH
e DELETE verificam a preparação sob o lock da raiz antes de alterar filhos;
retornam conflito sem modificar itens. A atualização também faz uma verificação
prévia antes da consulta Catalog; a verificação transacional continua obrigatória.
As validações de produto existentes de adição/atualização continuam fora da
transação e podem falhar antes da tentativa de escrita.

Concluir muda preparação para COMPLETED e carrinho para CHECKED_OUT na mesma
transação, preservando itens. Replay com mesmo orderId não altera timestamps;
outro orderId ou aborto de concluído retorna conflito. Outra adição sem cartId
pode criar novo ACTIVE; o carrinho histórico não é reaberto.

Abortar PREPARED preserva snapshot e itens e libera somente a própria
associação. Replay não libera uma preparação posterior. Aborto sem preparação
cria ABORTED sem cartId/itens e impede preparação atrasada, inclusive após
reinício. Não há TTL, exclusão automática ou reconciliador nesta entrega.

## Persistência e concorrência

V3__create_cart_checkouts.sql acrescenta cart_checkouts e cart_checkout_items,
com PK por checkoutId, FK local ao Cart, estados/timestamps coerentes,
quantidade positiva e unicidade de produto no snapshot. Índice parcial garante
uma associação PREPARED/COMPLETED por carrinho, permitindo novas intenções após
aborto. Não há FK entre microsserviços. V1/V2 e dados antigos ficam intactos.

Transições de uma preparação existente bloqueiam primeiro checkout e depois
carrinho. Escritas normais bloqueiam apenas a raiz e consultam a preparação sem
adquirir outro lock explícito. Na criação, a ausência de checkout não pode ser
bloqueada: após esperar pela raiz, a transação relê sem lock a identidade para
resolver replay já confirmado, sem inverter a ordem de locks das transições.

Se duas criações ainda disputarem checkoutId, a perdedora reverte integralmente
antes da releitura em nova transação. Apenas a violação de pk_cart_checkouts
ativa essa retomada; outras falhas de integridade não são ocultadas.
Nenhuma transação inclui HTTP.

## Arquivos principais

- `src/main/resources/db/migration/V3__create_cart_checkouts.sql`
- `src/main/java/br/com/hadryan/shosphere/cart/model/CartCheckout.java`
- `src/main/java/br/com/hadryan/shosphere/cart/model/CartCheckoutItem.java`
- `src/main/java/br/com/hadryan/shosphere/cart/model/enums/CartCheckoutStatus.java`
- `src/main/java/br/com/hadryan/shosphere/cart/repository/CartCheckoutRepository.java`
- `src/main/java/br/com/hadryan/shosphere/cart/repository/CartRepository.java`
- `src/main/java/br/com/hadryan/shosphere/cart/service/impl/CartCheckoutService.java`
- `src/main/java/br/com/hadryan/shosphere/cart/service/impl/CartCheckoutTransaction.java`
- `src/main/java/br/com/hadryan/shosphere/cart/service/impl/CartCheckoutSnapshot.java`
- Guardas nos componentes existentes AddCartItemTransaction,
  UpdateCartItemTransaction, RemoveCartItemUseCaseImpl e UpdateCartUseCaseImpl.

Testes, comandos e resultados em [TESTING.md](TESTING.md). A aplicação da V3
nas verificações desta entrega fica restrita a schemas temporários de teste.
A ativação do protocolo entre serviços continua dependente das etapas 44–48
e da estratégia conjunta aprovada; não usar preparações isoladas em produção.
