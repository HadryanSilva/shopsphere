# Recuperação agendada de estoque — microtarefa 54

Implementa o agendamento aprovado no [contrato Order–Inventory](../contracts/order-inventory-proposal.md),
sobre a [persistência](STOCK_PERSISTENCE.md) e o [cliente/fluxo](STOCK_FLOW.md)
das etapas 52–53. O POST inicial continua sem aguardar Inventory.

## Execução e cooperação com Cart

StockRecoveryJob executa lotes limitados de trabalho explicitamente persistido.
Não seleciona pedidos apenas por PENDING_STOCK. Só fica ativo quando
checkout.snapshot.enabled e stock.recovery.enabled são true. Snapshot continua
false por padrão; habilitá-lo ativa a recuperação de estoque por padrão.
Desabilitar o job não apaga trabalho nem impede retomada depois de reabilitado.

O job de conclusão do Cart permanece separado. WAITING_CART consulta a intenção
durável e só permite reservar após COMPLETED. Se o processo cair entre o commit
de COMPLETED e a elegibilidade do estoque, a próxima passagem recupera o avanço,
sem JWT do utilizador, callback em memória ou replay público.

Quando não existe TaskScheduler customizado, a configuração do worker fornece
taskScheduler com duas threads para os jobs de Cart e estoque. Uma execução
HTTP lenta do Cart não ocupa a única thread que poderia iniciar estoque, e
vice-versa. Cada job continua com fixedDelay, sem sobrepor suas próprias
passagens no mesmo processo. Um scheduler customizado deve oferecer capacidade
equivalente para as duas tarefas.

## Claims, leases e transações

A V6__claim_stock_work.sql acrescenta claim_token, claimed_until e last_warning_at
à tabela local stock_work, com check para o par identidade/prazo do claim.
Não altera V1–V5, estados públicos, reservas ou snapshots financeiros.

due ordena por nextAttemptAt/orderId, exclui CLOSED/PAYMENT_PENDING, tentativas futuras e leases
vigentes e usa SKIP LOCKED. claim revalida elegibilidade e grava token UUID e
prazo em transação curta. A agenda e o lease são campos distintos: progresso ou
falha do fluxo não permite que outro worker adquira imediatamente o mesmo
trabalho enquanto uma chamada HTTP ainda está em execução.

O claim faz commit antes de StockReservationFlow.resume. Não há lock ou
transação de banco atravessando HTTP. Uma falha é isolada por pedido; os demais
itens/lotes continuam. Reinício ou falha no bookkeeping deixa o claim elegível
após o lease, preservando a identidade da reserva e a decisão de aborto.

finish só libera o claim e registra aviso quando seu token ainda é o atual.
Uma execução antiga não limpa o claim de quem assumiu depois. O lease reduz
sobreposição; não cancela HTTP despachado nem garante execução exclusiva após
uma pausa maior que seu prazo. Transições revalidam o estado atual e preservam
ABORT_PENDING/CLOSED; o aborto durável no Inventory impede PUT tardio. Não há
promessa de entrega exatamente uma vez.

## Agenda, falhas e monitoramento

| Situação | Agenda |
|---|---|
| WAITING_CART sem conclusão | Nova leitura após monitor-interval, sem reservar/gastar TTL. |
| ACTIVE validado | Próxima observação no menor prazo entre monitor-interval e expiresAt. |
| Falha transitória/configuração em RESERVE_PENDING ou ABORT_PENDING | initial-backoff dobrando pela contagem persistida até max-backoff. |
| Falha transitória em RESERVED | Backoff limitado também a monitor-interval, sem cancelar por indisponibilidade. |
| REVIEW_REQUIRED | Apenas GET diagnóstico; intervalo mínimo max-backoff, sem mutações incompatíveis automáticas. |
| CLOSED | Sem nova agenda, sem HTTP e sem reabertura. |

Contagem é acumulada por falhas e não é zerada ao trocar de processo. Não há
abandono por idade ou quantidade de tentativas. Falha inesperada antes de o
fluxo registrar seu retry recebe PERSISTENCE_FAILURE no finish; uma falha já
registrada não é contada novamente. Respostas de negócio/erros não são
classificados por texto de Problem Details.

Prazo passado solicita reconciliação/aborto; só prova remota válida permite
CANCELLED. CONFIRMED inesperado ou incompatibilidade permanecem em revisão,
com decisão anterior preservada. O estado público e os valores financeiros
seguem as regras das etapas 52–53, sem confirm ou integração de pagamento.

## Configuração

Valores em application.yaml; todas as durações devem ser positivas:

| Propriedade stock.recovery.* | Variável | Padrão |
|---|---|---|
| enabled | SHOPSPHERE_STOCK_RECOVERY_ENABLED | true, condicionado também ao snapshot |
| interval | SHOPSPHERE_STOCK_RECOVERY_INTERVAL | PT60S |
| batch-size | SHOPSPHERE_STOCK_RECOVERY_BATCH_SIZE | 50, inteiro positivo |
| lease | SHOPSPHERE_STOCK_RECOVERY_LEASE | PT60S |
| initial-backoff | SHOPSPHERE_STOCK_INITIAL_BACKOFF | PT60S |
| max-backoff | SHOPSPHERE_STOCK_MAX_BACKOFF | PT15M, não menor que initial-backoff |
| monitor-interval | SHOPSPHERE_STOCK_MONITOR_INTERVAL | PT60S, no máximo PT60S e não menor que interval |
| warn-after | SHOPSPHERE_STOCK_WARN_AFTER | PT15M |

A agenda de monitoramento é limitada a 60 segundos. A execução efetiva depende
do tempo das chamadas, tamanho do lote e carga: fixedDelay não é garantia de
tempo real. A reserva pode expirar antes da passagem seguinte; o protocolo de
reconciliação permanece seguro. Timeouts HTTP continuam configurados na 53.

Após warn-after de idade pendente, o job emite aviso e continua reconciliando.
last_warning_at limita o aviso ao mesmo intervalo mesmo após reinício ou troca
de worker. Logs incluem apenas orderId/checkoutId, fase, contador e categoria
segura; exceções, JWTs, secrets, endereço, chaves públicas e body downstream não
são registrados. REVIEW_REQUIRED recebe consultas/avisos limitados, sem endpoint
manual novo ou alteração de SQL como procedimento normal.

## Validação e limites

Testes de agendamento, configuração e logs em StockRecoveryJobTests. PostgreSQL
em StockWorkPersistenceTests cobre SKIP LOCKED, claims concorrentes, reinício,
backoff, monitoramento, rollback de bookkeeping, aviso persistido e worker que
retoma após perder o lease. O cliente remoto é simulado nesses testes; o caso
do PUT atrasado simula a barreira durável já implementada no Inventory.
Comandos/resultados em [TESTING.md](TESTING.md).

V6 é aplicada apenas em schemas temporários dos testes nesta entrega. Nenhuma
API pública, regra JWT, dependência, Gateway ou código do Inventory mudou.
JARs reais de todos os serviços, inclusive Inventory, falhas entre processos e
tokens reais foram validados posteriormente na [microtarefa 55](STOCK_OPERATION.md).

Na [microtarefa 59](PAYMENT_HANDOFF.md), a aceitação de pagamento transfere
StockWork RESERVED para PAYMENT_PENDING em commit atômico com o trabalho
financeiro local. Essa fase não é selecionada por due/claim, limpa o claim
anterior e ignora mutações de execuções antigas. O worker de estoque não
reassume essa reserva após o lease; a coordenação passa ao fluxo de pagamento.
