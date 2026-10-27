# Intenção de checkout e origem do pedido — microtarefa 45

Implementa a persistência local aprovada na
[microtarefa 42](../contracts/cart-checkout-proposal.md).
O fluxo público existente ainda usa CreateOrderUseCaseImpl e
CreateOrderTransaction. Não há chamada ao protocolo interno do Cart, aquisição
de token de serviço, endpoint novo ou reconciliador nesta entrega.

## Modelo e migration

V4__persist_checkout_intents.sql acrescenta source_cart_id nullable em orders,
com índice único para valores não nulos. Pedidos antigos e os criados pelo
fluxo atual mantêm origem nula: não há backfill ou inferência por itens iguais.
Order.assignSourceCart permite associar origem a um pedido novo, sem permitir
troca de origem nem atribuição retrospectiva a um pedido legado persistido.
OrderResponse e OrderStatus permanecem inalterados.

checkout_intents guarda checkoutId, customerId, chave pública original, endereço
completo, estado, cartId, orderId e falha de negócio segura. checkout_intent_items
guarda o snapshot imutável de productId/quantity, sem preços. checkoutId é gerado
uma vez e difere da chave pública; cliente/chave é único. A referência a orders
é uma FK local e orderId é único. Não há FK ou acesso ao banco do Cart.

Os estados internos são PREPARING, PREPARED, COMPLETION_PENDING, COMPLETED,
ABORT_PENDING e ABORTED. CHECKs garantem coerência de estado/referência/falha,
quantidades positivas, contadores não negativos, categorias conhecidas e
agenda ausente nos estados terminais. O snapshot não aceita produtos duplicados.
O registro usa @Version, além dos bloqueios pessimistas das transações.

O endereço é copiado e comparado exatamente nos seis campos atuais; null e
string vazia em addressLine2 continuam distintos. Falhas de negócio usam
CheckoutFailureReason, que determina 404/409 sem armazenar texto recebido de
outro serviço. Falhas transitórias usam CheckoutRetryCategory, também enum.

nextAttemptAt, attemptCount e lastFailureCategory persistem a retomada futura.
attemptCount incrementa ao registrar uma tentativa falhada. O cálculo de backoff,
seleção de lotes, execução de HTTP e alertas operacionais pertencem às etapas
46–47; nenhum agendamento foi ativado aqui. Não há expiração ou exclusão automática.

## Transações e idempotência

CheckoutIntentService resolve apenas a corrida de criação por cliente/chave:
a violação de uk_checkout_intents_customer_key é relida em outra transação
depois do rollback da perdedora. Outros erros de integridade não são ocultados.
Chave já associada a um pedido sem intenção não ganha uma intenção artificial;
o replay legado continua resolvido pelo fluxo público existente.

CheckoutIntentTransaction fornece transações REQUIRES_NEW curtas para criar,
ler, preparar, gravar pedido, registrar aborto, confirmar conclusão/aborto e
guardar uma falha transitória. Não contém HTTP. Leitura e transições bloqueiam
a raiz para obter estado e itens consistentes, inclusive quando a preparação
adiciona o snapshot após a criação inicial.

Na gravação do pedido, a transação revalida estado, cliente, chave, endereço,
origem e productId/quantity do candidato. Grava pedido/itens com sourceCartId e
passa a intenção para COMPLETION_PENDING no mesmo commit. Uma falha em qualquer
parte reverte tudo. A resposta inicial usa a representação relida do banco,
inclusive a precisão de updatedAt, para coincidir com o replay após reinício.
Uma intenção com pedido já gravado devolve o pedido persistido, preservando os
snapshots financeiros. Não recalcula preços nem substitui itens nesse replay.

A intenção bloqueada serializa aborto versus criação: ABORT_PENDING impede
gravação tardia, e COMPLETION_PENDING impede aborto. Repetições equivalentes de
preparação/conclusão/aborto não modificam timestamps. Registros ABORTED continuam
consumindo cliente/chave. Resultado de falha de uma etapa antiga não modifica
uma intenção que já mudou de estado. Um rollback pode deixar o objeto candidato
em memória com ID atribuído: construir outro candidato ao tentar novamente;
não reutilizar como se tivesse sido persistido.

## Arquivos principais

- `src/main/resources/db/migration/V4__persist_checkout_intents.sql`.
- `src/main/java/br/com/hadryan/shosphere/order/model/Order.java`.
- `src/main/java/br/com/hadryan/shosphere/order/model/CheckoutIntent.java` e `CheckoutIntentItem.java`.
- `src/main/java/br/com/hadryan/shosphere/order/model/enums/CheckoutIntentStatus.java`,
  `CheckoutFailureReason.java` e `CheckoutRetryCategory.java`.
- `src/main/java/br/com/hadryan/shosphere/order/repository/CheckoutIntentRepository.java`.
- `src/main/java/br/com/hadryan/shosphere/order/service/impl/CheckoutIntentService.java`,
  `CheckoutIntentTransaction.java` e `CheckoutIntentSnapshot.java`.

Testes novos: CheckoutIntentTests e CheckoutIntentPersistenceTests.
OrderServiceApplicationTests simula o novo componente transacional, preservando
o teste do fluxo público real com repositório e integrações já simulados.
Comandos e resultados em [TESTING.md](TESTING.md). V1–V3 ficam intactas;
V4 foi exercitada somente em schemas temporários dos testes. A ativação entre
serviços continua dependente das etapas 46–48 e da drenagem aprovada no contrato.
