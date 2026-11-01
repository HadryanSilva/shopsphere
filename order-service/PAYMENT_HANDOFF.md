# Aceitação de pagamento e transferência de estoque — microtarefa 59

Implementa o commit local aprovado no
[contrato Payment–Order–Inventory](../contracts/order-payment-inventory-proposal.md).
Esta etapa registra intenção e transfere coordenação; não chama Payment ou
Inventory e não captura, reembolsa ou confirma pedidos. Cliente/coordenador e
recuperação financeira pertencem às etapas 60–61.

## HTTP público

```http
PUT /api/v1/orders/{orderId}/payment
Authorization: Bearer <JWT do proprietário>
```

Body ausente ou `{}`. Qualquer campo, array, literal ou JSON inválido retorna
400. Não há nova Idempotency-Key: a identidade é o pedido. `customerId` vem do
sub autenticado, seguindo a convenção atual; query parameters não o substituem.
Pedido ausente/de terceiro retorna o mesmo 404, inclusive para ADMIN.

Uma nova solicitação exige Order PENDING_PAYMENT e StockWork RESERVED/ACTIVE,
sem aborto, motivo de compensação ou revisão registrados. O total e a moeda
vêm exclusivamente do pedido persistido: EUR, valor positivo e exatamente
representável em NUMERIC(19,2). Não há nova consulta de preço ou confiança em
valor enviado pelo cliente. Estado/termos incompatíveis ou ausência de trabalho
de estoque elegível retornam 409 sem criar pagamento.

Aceitação nova ou replay pendente retorna **202**, OrderResponse e Location
`/api/v1/orders/{orderId}`. O GET existente e o replay do checkout mostram
`paymentStatus=PROCESSING`; o pedido continua PENDING_PAYMENT. Pedidos sem
solicitação continuam com paymentStatus null. Repetições preservam o mesmo
trabalho, snapshot, versões e datas quando não houve nova transição.
Replay de trabalho DONE/CLOSED retorna 200 com o pedido atual; esta etapa não
produz essas fases, que dependem das provas e transições do coordenador futuro.

O Gateway já encaminha `/api/v1/orders/**` e exige autenticação. Essa política
não mudou; novos testes verificam PUT, Bearer/body, status/Location e rejeição
anônima. Caminhos diretos/internos de Payment continuam sem exposição.

## Commit local e bloqueios

V7 acrescenta `orders.payment_status`, `payment_work` e a fase de estoque
PAYMENT_PENDING. Não altera V1–V6, não inscreve pedidos automaticamente e não
fabrica trabalhos a partir de status. A PK de payment_work é order_id, com FK
apenas para orders no próprio banco. O trabalho guarda total/moeda imutáveis,
fase CAPTURE_PENDING, datas, versão e agenda inicial; provas remotas, claims e
recuperação serão acrescentados quando implementados nas etapas próprias.

Após uma consulta de existência/proprietário que não carrega entidade, a
transação bloqueia **StockWork → Order → PaymentWork**. A mesma raiz de estoque
serializa solicitações concorrentes e a decisão de aborto do worker atual.
Criar o trabalho, preencher paymentStatus e transferir StockWork fazem parte
do mesmo commit. Falha de qualquer gravação reverte os três efeitos.
A resposta usa a representação do banco, inclusive a precisão das datas.

Transferir para PAYMENT_PENDING limpa claim_token/claimed_until, retira a agenda
do estoque e preserva a prova ACTIVE. A fase não participa de due/claim. Os
guards de observação, erro, revisão e aborto ignoram retornos antigos depois
da transferência; finish de um claim antigo não modifica o trabalho.
StockReservationFlow retorna nessa fase sem chamar Inventory.

Se ABORT_PENDING vencer o bloqueio, a aceitação é recusada. Se a transferência
vencer, o worker antigo não consegue registrar aborto, nem depois de perder
o lease. Nenhuma chamada HTTP fica sob transação. Um aborto já autorizado não
pode coexistir com uma transferência aceita, pois seu estado durável a impede.

## Limites desta etapa

ACTIVE é uma observação anterior, não garantia de validade futura da reserva.
A aceitação não confirma estoque nem pagamento. Até concluir 60–61, solicitações
aceitas permanecem CAPTURE_PENDING/PROCESSING sem worker financeiro; a reserva
pode expirar no Inventory. O coordenador terá de consultar e resolver esse
estado remoto, seguindo as provas exigidas no contrato, antes de concluir ou
compensar dinheiro. O worker antigo não reassume a reserva por conta própria.

O [coordenador da etapa 60](PAYMENT_FLOW.md) implementa essas transições e provas;
a [microtarefa 61](PAYMENT_RECOVERY.md) acrescenta a retomada automática por worker.
O PUT inicial continua sem aguardar HTTP remoto.

Nenhuma mudança no Payment, Inventory, Cart, Catalog, frontend, autenticação,
dependências ou configuração Keycloak. Validação em [TESTING.md](TESTING.md).
