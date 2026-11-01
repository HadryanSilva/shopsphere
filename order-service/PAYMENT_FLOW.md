# Coordenador Order–Payment–Inventory — microtarefa 60

Implementa o [contrato aprovado](../contracts/order-payment-inventory-proposal.md)
sobre a [aceitação e transferência](PAYMENT_HANDOFF.md) da etapa 59.
PaymentFlow.resume(orderId) retoma um trabalho persistido, uma fase por chamada.
O PUT público continua fazendo apenas o commit local e respondendo 202; não
aguarda nem dispara HTTP financeiro. Agendamento, claims/leases, contagem de
tentativas e backoff pertencem à etapa 61. Não há endpoint manual novo.

## Decisões e provas

| Fase persistida | Operação e avanço |
|---|---|
| CAPTURE_PENDING | Ler Inventory. Só ACTIVE compatível permite GET/PUT Payment com os termos persistidos. PROCESSING mantém pendência e permite repetir PUT; CAPTURED leva a CONFIRM_PENDING; recusa/estado terminal leva à resolução. |
| CONFIRM_PENDING | Revalidar prova Payment e Inventory; CONFIRMED compatível conclui, ACTIVE permite POST confirm. 404/409 de confirmação leva à resolução; falhas técnicas mantêm a fase. |
| RESOLUTION_PENDING | Ler estoque e, se ACTIVE/ausente, obter abort durável. CONFIRMED com captura comprovada conclui. RELEASED/EXPIRED/ABORTED válido registra prova segura e avança para CANCEL_PAYMENT_PENDING. Não inicia confirmações novas. |
| CANCEL_PAYMENT_PENDING | Usar a prova segura já commitada e cancelar/compensar no Payment. CANCELLED/DECLINED/REFUNDED comprovados encerram; REFUND_PENDING mantém pedido pendente. Não volta à confirmação. |
| DONE / CLOSED | Sem novo HTTP ou reabertura. |
| REVIEW_REQUIRED | Sem mutações automáticas; consultas/avisos limitados serão tratados na recuperação da etapa 61. |

Order CONFIRMED exige captura integral compatível e reserva CONFIRMED. A
conclusão grava Order CONFIRMED/CAPTURED, StockWork SETTLED e PaymentWork DONE
na mesma transação. SETTLED é distinto de CLOSED e não pertence ao worker
antigo de estoque.

Order CANCELLED exige prova de que não haverá consumo tardio e prova monetária
terminal segura. O fechamento grava o estado monetário comprovado, StockWork
CLOSED e PaymentWork CLOSED juntos. Não repõe estoque físico por suposição.
O Inventory decide consumo, expiração e devolução do reservado.

GET/404 não prova ausência futura: no estoque exige abort durável; no Payment,
antes de capturar permite PUT idempotente, mas nunca autoriza fechamento seguro.
409 não é classificado por texto. Abort 409 exige consulta: confirmação vencedora
com captura comprovada conclui; falta de prova compatível exige revisão.
CONFIRMED com pagamento terminal sem captura exige revisão, sem reembolso ou
nova cobrança para ocultar a inconsistência.

Timeout após captura/confirm/abort/reembolso deixa a fase persistida retomável.
Repetição/consulta usa o mesmo orderId, valor e moeda. A decisão de resolver
estoque faz commit antes de abort; a prova segura faz commit antes de cancel.
Uma confirmação já autorizada pode continuar em voo; a barreira do Inventory
impede que vença após liberação/aborto. Uma captura atrasada é barrada ou
reembolsada pelo cancel durável do Payment. Retornos antigos não reabrem fases.
CONFIRMED que contradiga uma prova segura exige revisão, inclusive quando a
resposta chega após CLOSED. Nesse caso, conserva o status público anterior e
marca paymentStatus REVIEW_REQUIRED, sem reabrir mutações financeiras.

## Persistência e validação

V8 preserva V1–V7 e acrescenta status/datas da prova monetária, prova de estoque
seguro/ausente e categoria de falha em payment_work. StockWork armazena as datas
e o estado observados da reserva; passa a admitir CONFIRMED/SETTLED. Constraints
rejeitam provas incompletas, fechamento financeiro inseguro e SETTLED sem
CONFIRMED. Não há inserção automática de trabalhos nem alteração de bancos de
outros serviços.

Transações curtas seguem StockWork → Order → PaymentWork. Movimento financeiro
remoto e commit local não são uma única transação; a recuperação usa identidades
idempotentes e provas. HTTP sempre ocorre fora da transação de banco. Falha no
commit local após sucesso remoto preserva a fase anterior para reconciliação.
Não há promessa de entrega exatamente uma vez.

O cliente Payment lê JSON decimal com BigDecimal, exige identidade, total/moeda
exatos, estado reconhecido, datas válidas e nulls explícitos. A persistência
também valida essas provas e preserva createdAt/capturedAt/refundedAt estáveis,
sem regredir estados ou aceitar snapshots antigos como estado novo. O cliente
Inventory confirma pelos itens da intenção e valida identidade, quantidades,
createdAt/expiresAt estáveis e updatedAt. O relógio do Order não declara a
reserva expirada nem substitui a decisão remota.

Incompatibilidade de prova leva a REVIEW_REQUIRED; 401/403 são falhas técnicas,
não DECLINED. Indisponibilidade e falha de persistência conservam pendência e
categoria segura. Logs/exceções não carregam texto downstream, JWT ou secret.

## HTTP e configuração

Reutiliza CheckoutServiceTokenProvider, client_credentials de shopsphere-order
e a role PAYMENT_SERVICE provisionada na etapa 57. Não encaminha JWT do cliente
nem altera Keycloak. Os clientes ficam ativos com checkout.snapshot.enabled,
conforme a configuração existente. Não há dependência ou upgrade novo.

| Propriedade | Variável | Padrão |
|---|---|---|
| services.payment.url | SHOPSPHERE_PAYMENT_URL | http://localhost:8085 |
| payment.connect-timeout | SHOPSPHERE_PAYMENT_CONNECT_TIMEOUT | 2000 ms |
| payment.read-timeout | SHOPSPHERE_PAYMENT_READ_TIMEOUT | 5000 ms |

Timeouts devem ser positivos; URL base exige HTTP(S), host e ausência de
credenciais/query/fragment/path extra. Sem redirect ou retry de transporte.
Inventory conserva seus timeouts existentes; POST confirm usa o mesmo cliente
de serviço. Payment continua sendo o simulador persistente de desenvolvimento,
sem PSP/cartões/dinheiro real. Validação em [TESTING.md](TESTING.md).
