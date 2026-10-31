# Trabalho de estoque e transições locais — microtarefa 52

Implementa a persistência aprovada no
[contrato Order–Inventory](../contracts/order-inventory-proposal.md).
O fluxo por snapshot grava pedido/itens, origem, COMPLETION_PENDING e um
StockWork em WAITING_CART no mesmo commit local. Falha ao gravar qualquer parte
reverte tudo. O replay devolve o pedido atual sem duplicar trabalho ou itens.

## Modelo e migration

A nova V5__persist_stock_work.sql cria stock_work com order_id como PK/FK local
para orders e checkout_id único/FK local para checkout_intents. Não existem
referências SQL ao Inventory, Cart ou Catalog. V1–V4 permanecem intactas.
As constraints validam fase, decisão/razão, observação/datas, agenda, categorias,
contagem e versão. O índice parcial idx_stock_work_due prepara a seleção futura.

Os itens persistidos do pedido são a intenção imutável: productId/quantity,
sem recarregar Cart/Catalog ou manter uma segunda cópia modificável. O snapshot
local expõe uma lista imutável e não contém endereço, chave pública ou token.
As transições de estoque não alteram origem, endereço, itens, moeda ou valores.

São persistidos fase, decisão de aborto, razão segura, última observação aceita
(estado/datas da reserva), próxima tentativa, contador, categoria de falha,
timestamps e versão otimista. Razões/categorias são enums locais; não se guarda
texto downstream. O contador aumenta ao registrar falha e não encerra trabalho.

Somente pedidos novos do fluxo por snapshot recebem trabalho. A migration não
seleciona pedidos por PENDING_STOCK, não fabrica trabalho para registros
existentes e não altera o fluxo anterior CreateOrderTransaction. A seleção
checkout.snapshot.enabled e seu padrão false permanecem iguais.

## Transições e efeito público

| Fase | Condição/efeito |
|---|---|
| WAITING_CART | Pedido PENDING_STOCK. cartCompleted consulta a intenção durável e só avança se COMPLETED. |
| RESERVE_PENDING | Pronto para futura reserva; falhas técnicas mantêm PENDING_STOCK. |
| RESERVED | ACTIVE equivalente validado; pedido PENDING_PAYMENT e agenda de monitoramento. |
| ABORT_PENDING | Decisão terminal registrada; preserva status público anterior até prova remota. ACTIVE atrasado não desfaz a decisão. |
| CLOSED | Prova de RELEASED/EXPIRED equivalente, ou ABORTED sem reserva após decisão local; CANCELLED no mesmo commit e sem nova agenda. |
| REVIEW_REQUIRED | Identidade/itens/datas incompatíveis ou CONFIRMED inesperado; preserva status público e decisão anterior, sem novas mutações automáticas. |

StockWorkTransaction usa transações REQUIRES_NEW curtas, sem HTTP. Bloqueia
trabalho e depois pedido; ao verificar conclusão do Cart, lê a intenção com lock.
A coordenação existente de checkout continua sem adquirir locks de trabalho.
Elegibilidade é recuperável por leitura após reinício, sem depender de callback
entre o commit de COMPLETED e a mudança de WAITING_CART.

Observações locais validam orderId, conjunto exato de produtos/quantidades sem
duplicados, estado e datas. A primeira observação fixa createdAt/expiresAt;
replay não pode renovar prazo. Observação mais antiga não substitui a aceita.
CONFIRMED inesperado exige revisão; nunca chama Order.confirm().

O prazo local não prova expiração: ACTIVE com prazo passado continua observado
como ACTIVE até reconciliação remota. Indisponibilidade/credenciais inválidas
registram categoria/agenda sem cancelar. ABORT_PENDING preserva a primeira razão
e impede retorno a RESERVED, inclusive após reinício ou disputa entre threads.

aborted recebe a identidade da prova e uma observação terminal opcional. O
cliente da próxima etapa deverá validar o envelope ABORTED antes dessa chamada.
Se uma reserva já foi observada, prova com reservation null exige revisão.
Um GET/404 isolado não chama essa transição. CLOSED é estável e não reabre por
resposta ACTIVE tardia. REVIEW_REQUIRED também preserva a decisão, sem resolução
automática nesta entrega.

Mudanças de fase e status público são atômicas. Falha de constraint ou versão
reverte ambos. OrderResponse mantém o formato e paymentStatus null; consulta e
replay passam a refletir o status atual quando uma transição local é aplicada.

## Arquivos e limites

- model/StockWork.java e model/enums/StockWorkPhase, StockWorkReason,
  StockFailureCategory e StockReservationStatus.
- repository/StockWorkRepository.java.
- service/impl/StockWorkTransaction, StockWorkSnapshot e StockReservationObservation.
- service/impl/CheckoutIntentTransaction.java, no commit do pedido por snapshot.
- src/main/resources/db/migration/V5__persist_stock_work.sql.

Os caminhos Java partem de src/main/java/br/com/hadryan/shosphere/order/.
Testes: StockWorkTests e StockWorkPersistenceTests; comandos/resultados em
[TESTING.md](TESTING.md).

Não há cliente Inventory, chamadas HTTP, limite de 100 produtos no checkout,
worker, claims/lease, cálculo de backoff ou ativação automática das transições
nesta etapa. Esses pontos pertencem às microtarefas 53–54; a integração real,
à 55. Nenhuma API, segurança, dependência ou configuração de Keycloak mudou.
