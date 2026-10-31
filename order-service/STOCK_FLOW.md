# Cliente e reconciliação Order–Inventory — microtarefa 53

Implementa a etapa 53 do [contrato aprovado](../contracts/order-inventory-proposal.md),
usando o [trabalho persistido na 52](STOCK_PERSISTENCE.md).

## Checkout e limite

O modo por snapshot rejeita mais de 100 produtos distintos antes de consultar
Catalog ou criar pedido/trabalho de estoque. Usa a razão durável existente
PRODUCT_CONFLICT (409) e o mesmo protocolo de aborto/replay/recuperação do Cart.
Não há divisão de reserva ou limitação na edição do carrinho. Cem produtos
continuam num único pedido/trabalho. O fluxo anterior não foi alterado.

O POST inicial continua retornando 201/Location e PENDING_STOCK após o commit
local, sem consultar Inventory. Nenhuma falha de estoque posterior substitui
essa criação por 502/503. GET e replay continuam lendo o status atual sem
recalcular preço ou alterar o formato de OrderResponse/paymentStatus.

## Cliente HTTP interno

InventoryReservationClient usa RestClient/JDK HttpClient, sem dependência nova.
Só implementa GET, PUT e POST /abort em /internal/api/v1/inventory/reservations
por orderId. Não chama confirm ou release. PUT/abort enviam somente items com
productId/quantity do pedido persistido, sem JWT do cliente, endereço, preço,
customerId, chave pública ou checkoutId como identidade da reserva.

Reutiliza CheckoutServiceTokenProvider e a identidade shopsphere-order por
client_credentials. O token precisa ter INVENTORY_SERVICE, azp shopsphere-order
e a audiência aceita pelo Inventory, além da autorização já usada pelo Cart.
401/403 do Inventory invalidam o cache de token sem repetir a chamada no cliente.
401/403 do token endpoint são classificados como falha de configuração; o Cart
preserva seus status/categorias de erro existentes. Nenhum secret é registrado.

Configuração adicionada em application.yaml, com snapshot false por padrão:

| Propriedade | Variável/padrão |
|---|---|
| services.inventory.url | SHOPSPHERE_INVENTORY_URL / http://localhost:8084 |
| stock.connect-timeout | SHOPSPHERE_STOCK_CONNECT_TIMEOUT / 2000 ms |
| stock.read-timeout | SHOPSPHERE_STOCK_READ_TIMEOUT / 5000 ms |

Timeouts devem ser positivos; URL deve ser uma base HTTP(S) sem credenciais,
query, fragmento ou path adicional. Redirects não são seguidos. Não há política
de retries no cliente; a repetição é explícita na reconciliação.

Sucesso do PUT aceita 201/200; GET e abort exigem 200. São validados orderId,
estado, itens/quantidades inteiras positivas sem duplicados, datas coerentes e
createdAt/expiresAt já observados, sem renovação. O envelope de aborto exige
status ABORTED e o campo reservation presente, null ou RELEASED/EXPIRED válido.
Texto de Problem Details não é interpretado, persistido ou incluído na exceção.

## Reconciliação explícita

StockReservationFlow.resume(orderId) suspende eventual transação externa com
NOT_SUPPORTED. Cada leitura/transição local usa StockWorkTransaction em
REQUIRES_NEW; HTTP ocorre entre essas transações. O commit revalida a intenção,
estado e decisão, inclusive após chamadas que estavam em voo.

| Observação/fase | Resultado |
|---|---|
| WAITING_CART | Só avança se a intenção durável do Cart estiver COMPLETED; sem HTTP enquanto aguarda. |
| RESERVE_PENDING | GET primeiro para recuperar resposta perdida; apenas GET/404 permite tentar PUT, após reler a decisão local. |
| PUT 404/409 | GET de reconciliação; ACTIVE vencedor é aceito. GET/404 registra ABORT_PENDING/RESERVATION_REJECTED antes do aborto remoto. |
| ACTIVE válido | RESERVED/PENDING_PAYMENT, preservando snapshots. Prazo aparentemente vencido solicita aborto; não cancela pelo relógio local. |
| RELEASED/EXPIRED válido | CLOSED/CANCELLED, sem PUT novo ou consumo definitivo. |
| ABORT_PENDING | Apenas aborto equivalente; CLOSED/CANCELLED exige a prova válida. Timeout mantém decisão e pendência. |
| Timeout/5xx/401/403 | Mantém fase/status e registra contador/categoria segura/próxima tentativa. |
| 400 inesperado, resposta incompatível, abort 404/409, GET/404 após RESERVED | REVIEW_REQUIRED, sem declarar falta de saldo ou cancelamento. |
| CONFIRMED inesperado | Revisão; nunca confirma pagamento/pedido nem desfaz consumo. |
| CLOSED | Sem HTTP, sem reabrir. |
| REVIEW_REQUIRED | Permite GET de diagnóstico, sem PUT/abort ou resolução automática. |

Uma resposta ACTIVE atrasada não desfaz ABORT_PENDING. GET/404 isolado nunca
prova aborto. Falha de commit após sucesso remoto deixa trabalho durável para
GET/replay com o mesmo orderId. Nova compra exige novo Cart ativo/chave/pedido;
Cart concluído não é reaberto após rejeição/expiração de estoque.

## Limites e validação

Esta etapa forneceu o cliente e a operação retomável, sem ligar resume ao POST,
replay público ou ao job do Cart. Na [54](STOCK_RECOVERY.md), um worker separado
passou a chamar resume, com claims/lease, backoff e avisos duráveis. A agenda
fixa de 60 segundos usada inicialmente na 53 foi substituída pela política de
monitoramento/retry da 54; a resposta pública inicial continua independente.

Sem nova migration: V1–V5 e os enums/contratos externos permanecem intactos.
Não houve mudança de Gateway, Inventory, Keycloak ou ativação do modo snapshot.
Testes usam servidor HTTP local para cliente/token e PostgreSQL temporário
para o fluxo, com cliente Inventory simulado e verificação de ausência de
transação durante as chamadas. Resultados em [TESTING.md](TESTING.md).
A integração com JARs/Inventory real e falhas entre processos pertence à 55.
