# Payment–Order–Inventory — proposta de contrato (microtarefa 56)

**Estado: contrato integral aprovado explicitamente pelo utilizador; microtarefa 56 concluída.**
Escopo exclusivamente backend, conforme orientação do utilizador. Nenhum
endpoint, schema, permissão, simulador ou fluxo descrito como novo abaixo foi
implementado nesta microtarefa. A aprovação confirma as oito decisões ao final
e autoriza a sequência 57–62, executando uma entrega por vez.

## Base verificada

| Área | Implementação existente |
|---|---|
| Checkout/estoque | Sequência 49–55 concluída; pedido e trabalho duráveis, Cart concluído antes da reserva, recuperação HTTP e compensação com aborto durável. |
| Order | PENDING_PAYMENT após observar ACTIVE; confirm() existe no modelo, mas não há API/fluxo de pagamento. OrderResponse.paymentStatus é sempre null. |
| Inventory | PUT/GET, confirm/release/abort internos, intenção por orderId/cliente/itens. Confirmar ACTIVE dentro do prazo consome físico/reservado; replay CONFIRMED é estável mesmo depois do prazo. |
| Limite de compensação | release/abort de CONFIRMED retorna conflito. Não há operação de estorno de consumo físico confirmado. |
| Recuperação atual | StockWork em RESERVED acompanha expiração; CONFIRMED inesperado exige REVIEW_REQUIRED. Essa política não pode continuar competindo com uma confirmação legítima de pagamento. |
| Payment | Apenas aplicação Spring Boot, application.properties com nome e teste de contexto; sem domínio, endpoints, cliente, migrations, provedor ou protocolo financeiro. |
| Build/banco | Payment usa Java 25/Spring Boot 4.1.1, JPA/Validation/Web MVC/Resource Server e PostgreSQL. init.sql já declara payment_db. Flyway ainda não está no build de Payment. |
| Migrations | Order V1–V6 e Inventory V1–V5; Payment ainda sem migrations. Próximas versões devem ser reconfirmadas na implementação. |

Fontes verificadas: [contrato Order–Inventory](order-inventory-proposal.md),
[operação integrada](../order-service/STOCK_OPERATION.md),
[recuperação de estoque](../order-service/STOCK_RECOVERY.md),
[Order](../order-service/src/main/java/br/com/hadryan/shosphere/order/model/Order.java),
[confirmação Inventory](../inventory-service/src/main/java/br/com/hadryan/shosphere/inventory/service/impl/TransitionReservationUseCaseImpl.java),
[Payment](../payment-service/src/main/java/br/com/hadryan/shosphere/payment/PaymentServiceApplication.java),
[build Payment](../payment-service/build.gradle) e [init.sql](../init.sql).

## Primeira entrega proposta

Implementar pagamento **simulado e persistente para desenvolvimento**, sem
cobrança real, PSP externo, dados de cartão ou integração bancária. Os estados
CAPTURED/REFUNDED representam efeitos do simulador, não movimentação de dinheiro
real. Integração com provedor real terá contrato próprio: não é suficiente
trocar uma URL e presumir as mesmas garantias transacionais.

Order orquestra por HTTP direto, mantém a propriedade do pedido e dos snapshots;
Inventory mantém estoque/reserva; Payment mantém a intenção monetária e seus
movimentos. Nenhum serviço lê tabelas de outro. Sem RabbitMQ, Outbox, Saga por
eventos, bibliotecas novas de provedor ou frontend nesta entrega.

Proposta: uma intenção de pagamento por pedido, sem nova cobrança desse mesmo
pedido após recusa/cancelamento/reembolso terminal. Nova compra exige novo
Cart/chave/pedido. Sem parcelas, pagamento parcial, reembolso parcial, renovação
de reserva, cancelamento público, histórico/listagem ou administração financeira.

## Entrada pública pelo Order

**Extensão proposta, ainda inexistente:**

```http
PUT /api/v1/orders/{orderId}/payment
Authorization: Bearer <token do utilizador>
```

Body ausente ou objeto vazio. Não aceitar amount, currency, customerId, itens,
preços, dados de cartão, método de pagamento ou resultado do simulador no body.
Campos enviados indevidamente retornam 400. O valor vem exclusivamente de
Order.totalAmount e currency persistidos; não reconsultar Cart/Catalog.

O proprietário é o sub do JWT validado. Pedido inexistente ou de outro cliente
retorna o mesmo 404, inclusive para ADMIN. A operação exige PENDING_PAYMENT e
trabalho de estoque RESERVED elegível, sem decisão anterior de aborto/revisão.
Estado incompatível retorna 409 sem criar trabalho ou cobrar. EUR e total
positivo, representável exatamente em NUMERIC(19,2), são os únicos suportados
nesta fase; não converter moeda ou arredondar valor silenciosamente.

O pedido é a identidade estável dessa operação idempotente: não é necessária
uma segunda chave pública. Chamadas concorrentes criam um único trabalho local
por orderId. Em nova solicitação/pendência, 202 com OrderResponse e Location
/api/v1/orders/{orderId}; replay de trabalho terminal retorna 200 com o pedido
atual. Não esperar Payment/Inventory para responder à aceitação inicial.
Falha antes do commit não pode ser apresentada como aceita.

GET /api/v1/orders/{orderId} continua sendo a consulta pública. O formato de
OrderResponse é preservado, mas **paymentStatus passará a ter valores** para
pedidos com esse trabalho; pedidos sem pagamento solicitado continuam com null.
Não é proposto endpoint público direto do Payment nem consulta pela chave do
checkout. Rotas/permissões já existentes do Gateway/Order devem ser testadas;
não alargar a exposição das APIs internas.

## Payment interno e identidade monetária

**Novos contratos candidatos, sujeitos à aprovação:**

| Operação | Contrato proposto |
|---|---|
| Capturar simulado | PUT /internal/api/v1/payments/{orderId}, body amount/currency. 201 inicial ou 200 replay quando houver resultado; 202 se ainda PROCESSING. |
| Consultar | GET /internal/api/v1/payments/{orderId}, 200 com representação; 404 ausente/inacessível. Somente leitura. |
| Cancelar/compensar | POST /internal/api/v1/payments/{orderId}/cancel, mesma intenção amount/currency. 200 com estado terminal seguro ou 202 REFUND_PENDING. Funciona mesmo antes da captura existir. |

```json
{"amount":25.00,"currency":"EUR"}
```

Representação proposta: orderId, status, amount, currency, createdAt, updatedAt,
capturedAt e refundedAt. Datas UTC; campos de instante ainda não aplicáveis
explicitamente null. Estados candidatos:

| Payment | Significado |
|---|---|
| PROCESSING | Intenção durável registrada, captura ainda não comprovada. |
| CAPTURED | Um movimento simulado de captura integral foi commitado. |
| DECLINED | Recusa de negócio definitiva; não houve captura e não haverá nova captura nessa identidade. |
| CANCELLED | Impedimento durável sem captura, inclusive cancelamento antes do primeiro PUT. |
| REFUND_PENDING | Decisão de compensar captura persistida; ainda falta prova do reembolso integral. |
| REFUNDED | Reembolso integral comprovado; captura não pode ser retomada. |

Recusa de negócio é um estado explícito na resposta bem-sucedida, não um 409
genérico interpretado pelo texto. Body inválido: 400. Intenção monetária
incompatível ou PUT após CANCELLED/REFUNDED: 409. Outro cliente: 404. JWT e
permissões seguem 401/403. Textos downstream não determinam decisões financeiras.

Payment vincula orderId ao azp autorizado e ao mesmo amount/currency imutável.
Usar BigDecimal/NUMERIC; validar precisão, sinal, moeda e equivalência monetária
sem conversão para double. Retentativas preservam identidade e valores.
Uma raiz durável e movimentos de captura/reembolso com unicidade por operação
devem impedir efeito duplicado e permitir auditoria, mesmo após reinício.

O cancelamento grava decisão durável e impede captura atrasada. Se a captura
venceu a corrida, a compensação exige reembolso; se o cancelamento venceu, a
captura não pode ocorrer. Ausência em GET/404 nunca comprova cancelamento.
Não resolver criação concorrente com SELECT ausente seguido de INSERT sem
tratar a disputa. No simulador, movimento e resultado monetário fazem commit
local juntos; intenção/decisão prévia sobrevivem a falhas antes do resultado.

Simulação de recusa, indisponibilidade e resposta perdida pertence ao adaptador
de desenvolvimento e aos testes, sem campo público que permita ao cliente
declarar pagamento aprovado. Sem PAN, CVV, contas bancárias, tokens de cartão
ou credenciais de PSP em código, payloads ou logs.

## Ordem das operações e prova de conclusão

Proposta: **capturar no Payment, depois confirmar no Inventory**. A reserva pode
expirar nesse intervalo; por isso a primeira entrega inclui compensação integral
e não pode terminar apenas na captura. Confirmar estoque antes da captura também
não resolveria a falha: o Inventory atual não desfaz consumo confirmado.

1. Registrar trabalho de pagamento e transferir a responsabilidade pela reserva
   no mesmo commit local do Order. O pedido permanece PENDING_PAYMENT.
2. Consultar/validar Inventory antes de enviar captura. ACTIVE observado é uma
   pré-condição, não garantia de que a confirmação futura vencerá a expiração.
3. Criar/consultar captura com orderId e total/moeda do pedido. Indisponibilidade
   mantém pendência. Só prova CAPTURED permite tentar confirmar o estoque.
4. Registrar a próxima decisão local antes do POST confirm, sem transação de
   banco atravessando HTTP. Conferir orderId, todos os itens/quantidades e datas
   estáveis das respostas; no Payment, conferir também valor/moeda exatos.
5. Com prova CAPTURED e reserva CONFIRMED equivalentes, marcar Order CONFIRMED
   e encerrar os trabalhos atomicamente. Nunca concluir pela resposta de apenas
   um serviço, pelo relógio local ou por timeout.

Em falha/rejeição/expiração, primeiro resolver o estoque. O coordenador pode
consultar/repetir confirmação idempotente enquanto estiver nessa fase; ao
entrar em resolução de compensação, deixa de iniciar novas confirmações.
Uma confirmação já enviada pode continuar em voo apesar do lease.

| Prova no Inventory | Próximo passo seguro |
|---|---|
| CONFIRMED equivalente e Payment CAPTURED comprovado | Aceitar a confirmação vencedora e concluir o pedido. Nenhum cancelamento/reembolso foi enviado ao Payment nessa ramificação. |
| RELEASED/EXPIRED equivalente ou abort durável válido, inclusive sem reserva | Persistir prova de que não haverá consumo tardio; só então cancelar/compensar no Payment. |
| GET/404 isolado | Chamar abort durável; ausência não encerra a resolução. |
| abort 409 | Consultar e validar a reserva: pode haver confirmação vencedora ou intenção incompatível. Não interpretar detail. |
| Identidade/itens/datas incompatíveis, ou CONFIRMED com Payment DECLINED/CANCELLED/REFUNDED | REVIEW_REQUIRED; não confirmar pedido, reembolsar ou repor estoque por suposição. |

Só enviar cancel ao Payment depois de prova de estoque terminal sem consumo.
Isso evita reembolsar enquanto um confirm tardio ainda pode consumir. A prova
de RELEASED/EXPIRED/abort impede esse confirm de vencer posteriormente.
Depois disso, resolver a cobrança com cancel idempotente: se não capturou,
CANCELLED/DECLINED; se capturou, REFUNDED. PROCESSING/REFUND_PENDING, timeout ou
GET/404 não são prova de ausência definitiva de cobrança.

**Order CANCELLED exige ambas as provas:** estoque sem consumo tardio e Payment
sem captura futura ou integralmente reembolsado. Reembolso pendente mantém o
pedido pendente e a recuperação ativa. Depois de iniciar compensação monetária,
não voltar à ramificação de confirmação. Se surgir consumo confirmado contrário
à prova de estoque já registrada, exigir revisão, sem tentar ocultar a inconsistência.

Não haverá cancelamento público nesta fase. A confirmação vencedora acima resolve
uma corrida técnica, não revoga uma solicitação de cancelamento do utilizador.
Reembolso após pedido já confirmado e devolução de consumo físico permanecem
fora do contrato; exigem desenho posterior específico.

## Coordenação local no Order

O worker de estoque atual não pode continuar abortando a mesma reserva enquanto
o novo fluxo confirma/captura. Propor fase interna de transferência, por exemplo
PAYMENT_PENDING, e finalização confirmada distinta de CLOSED, por exemplo SETTLED.
São nomes novos propostos, não estados já existentes. CLOSED atual continua
significando cancelamento com prova; CONFIRMED continua revisão se não houver
trabalho de pagamento que autorize e comprove a conclusão.

Criar trabalho de pagamento, transferir StockWork e preservar o snapshot numa
única transação curta, com unicidade por pedido. Ambos os workers revalidam
propriedade/fase depois do claim e antes de transições. A transferência invalida
o claim anterior de estoque e retira o trabalho de sua seleção; leituras ou
retornos antigos não podem iniciar aborto nem alterar estado depois dela.
ABORT_PENDING já registrado desqualifica a transferência. Se o aborto vencer
a disputa local, o pedido não entra em pagamento; se a transferência vencer,
o worker antigo não pode registrar a decisão de aborto. Lease vencido sozinho
não comprova ausência de HTTP em voo nem autoriza ignorar esses guards.
Bloqueios devem respeitar uma ordem global consistente entre StockWork, Order
e trabalho de pagamento; não introduzir aquisição inversa ou transação entre bancos.

Fases candidatas do novo trabalho: CAPTURE_PENDING, CONFIRM_PENDING,
RESOLUTION_PENDING, CANCEL_PAYMENT_PENDING, DONE, CLOSED e REVIEW_REQUIRED.
Guardar decisão/intenção, provas remotas, categoria segura, contador, agenda,
claim/lease e aviso duráveis. Novas migrations incrementais serão necessárias
no Order; não editar V1–V6 ou fabricar trabalhos por status de pedido.

Mapeamento público proposto:

| Situação | Order.status | paymentStatus |
|---|---|---|
| Sem solicitação | Comportamento atual | null |
| Captura em andamento | PENDING_PAYMENT | PROCESSING |
| Captura comprovada; estoque ainda em resolução | PENDING_PAYMENT | CAPTURED |
| Duas provas positivas | CONFIRMED | CAPTURED |
| Compensação ainda não comprovada | PENDING_PAYMENT | REFUND_PENDING, se houve captura; senão PROCESSING |
| Duas provas de encerramento seguro | CANCELLED | DECLINED, CANCELLED ou REFUNDED, conforme prova monetária |
| Inconsistência | Último estado público seguro | REVIEW_REQUIRED |

Não alterar ID, origem, endereço, itens, moeda ou valores. Pagamento não reabre
Cart nem revalida preços. Transições de status/updatedAt permanecem visíveis no
GET/replay do pedido; preenchimento de paymentStatus é uma extensão pública a
aprovar, não mudança silenciosa.

## Segurança, persistência e recuperação propostas

- Payment interno exige JWT validado no serviço, audiência shopsphere-api,
  nova realm role PAYMENT_SERVICE e azp shopsphere-order. Reutilizar a identidade
  de serviço, sem encaminhar token do utilizador ou criar cliente por conveniência.
  A nova role e seu vínculo precisam de autorização/provisionamento na etapa própria;
  nada é alterado no Keycloak nesta definição.
- HTTP direto e RestClient existente no Order, URL configurável; porta local
  candidata 8085. Timeouts de conexão/leitura 2000/5000 ms configuráveis, sem
  retries dentro do transporte. Payment não acessa Order/Inventory por banco.
- Payment usa payment_db já declarado no init.sql. Acrescentar apenas os módulos
  Flyway necessários, conforme convenção dos serviços existentes, sem upgrades
  de Java/Spring/Gradle ou novas dependências de PSP.
- Simulador explícito de desenvolvimento; não habilitar movimentação externa
  real nem tratar seus resultados como homologação de um PSP.
- Recuperação por trabalho gravado, não por consulta genérica de PENDING_PAYMENT.
  Lotes 50, varredura PT60S, backoff PT60S até PT15M e aviso após PT15M, ajustáveis
  e positivos. Antecipar reconciliação conforme prazo da reserva sem expirar pelo
  relógio do Order. Falha de autenticação é técnica e recuperável, não recusa.
- Claims/locks curtos, SKIP LOCKED e lease durável, sem HTTP sob transação.
  Validar chamadas/retornos após perda do lease; um worker antigo não pode
  reabrir captura, desfazer compensação ou liberar claim de outro.
- Continuar capturas/reembolsos pendentes sem abandono por tentativas/idade;
  revisão permite consultas/avisos limitados e não mutações incompatíveis.
  Logs só com IDs, fase, categoria, contagem e resultado seguro, sem endereço,
  chaves públicas, JWT, secret, dados de cartão ou texto do provedor/downstream.

## Microtarefas propostas

| Nº | Entrega | Critério de conclusão |
|---|---|---|
| 56 | Definir contrato backend Payment–Order–Inventory | Concluída: contrato integral e oito decisões aprovados explicitamente pelo utilizador. Sem implementação nesta definição. |
| 57 | Preparar Payment local, persistência e segurança | Datasource/JWT/Flyway configuráveis, schema inicial e erros; role/azp/audiência testados e provisionamento local limitado ao que for aprovado. Sem provedor externo. |
| 58 | Implementar captura e compensação simuladas duráveis | PUT/GET/cancel internos, intenção monetária imutável, movimentos únicos, barreira antes de captura e reembolso integral; rollback/reinício/concorrência com PostgreSQL. |
| 59 | Persistir pagamento e transferir coordenação no Order | PUT público por proprietário, trabalho único e transferência atômica do estoque; status/paymentStatus e prevenção de mutações de worker antigo validados. Sem HTTP financeiro nesse commit local. |
| 60 | Integrar coordenador Order–Payment–Inventory | Cliente de serviço, validação monetária, confirmação de estoque e matriz de resolução/compensação; nenhuma conclusão sem as duas provas. |
| 61 | Recuperar capturas, confirmações e compensações | Worker, agenda/backoff/claims, cooperação com estoque, retomada após lease e alertas; sem abandonar cobrança/reembolso pendente. |
| 62 | Validar integração e documentar operação | Seis JARs reais, schemas isolados, falhas após cada fronteira local/remota, tokens reais quando disponíveis e simulador claramente identificado. |

Uma entrega por vez, com testes e commit local. Não acrescentar funcionalidades
para preencher a tabela. Nomes internos, migrations e dependências devem ser
reconferidos no código ao iniciar cada etapa.

## Critérios de aceitação transversais

- Pedido de terceiro/ADMIN não proprietário não inicia nem consulta pagamento.
  Body com valor/identidade indevida é rejeitado; snapshot financeiro é a única fonte.
- Repetições concorrentes produzem uma captura integral e, se necessária, um
  reembolso integral por pedido, sem novos IDs para contornar falha/recusa.
- Cancel antes da captura impede PUT atrasado; captura que venceu é compensada
  uma vez. GET/404 não autoriza afirmar ausência futura de cobrança.
- Captura comprovada não confirma Order sem Inventory CONFIRMED. Estoque
  confirmado não produz Order CONFIRMED sem prova monetária correspondente.
- Expiração entre captura e confirm, confirmação perdida e confirm já em voo
  são reconciliados antes de reembolso. Prova de estoque terminal antecede cancel
  monetário; nenhuma confirmação tardia depois de compensação segura.
- Falha/resposta perdida de reembolso mantém pendência; pedido não é declarado
  CANCELLED enquanto puder existir cobrança sem restituição comprovada.
- Transferência ao novo coordenador impede o worker antigo de liberar reserva
  de um pedido em pagamento, inclusive com pausa maior que lease e dois Orders.
- Reinícios após intenção, captura, confirmação, decisão e reembolso retomam as
  mesmas identidades; payloads incompatíveis/401/403/5xx não viram sucesso financeiro.
- Valores/currency incompatíveis, CONFIRMED com Payment terminal sem captura ou
  estado remoto desconhecido exigem revisão; não há reposição física por suposição.
- Publicação de API, schemas e roles permanece limitada ao aprovado. Nenhuma
  tela, frontend, cartão real, PSP, evento, broker ou implantação é incluído.

## Decisões aprovadas pelo utilizador

1. Primeira entrega exclusivamente backend, com simulador monetário persistente
   em desenvolvimento, sem cobrança real/PSP/cartões ou frontend.
2. Uma intenção por pedido; recusa/cancelamento/reembolso terminal não permitem
   recobrar o mesmo pedido. Sem pagamentos/reembolsos parciais.
3. Novo PUT público idempotente por orderId no Order, body vazio, aceite assíncrono
   e consulta pelo GET existente; valor/moeda sempre do snapshot persistido.
4. Captura antes da confirmação de estoque, com reembolso integral incluído no
   mesmo escopo; Order CONFIRMED exige as duas provas positivas.
5. Resolver estoque antes de compensar dinheiro. Confirmação vencedora com
   captura comprovada conclui o pedido; compensação monetária só começa após
   prova de que não haverá consumo tardio. Sem cancelamento público nesta fase.
6. Barreira durável de cancelamento no Payment, inclusive antes de existir
   captura; Order CANCELLED só depois de prova de estoque seguro e monetária segura.
7. Transferência atômica do trabalho de estoque ao coordenador de pagamento,
   novas migrations/fases internas e preenchimento controlado de paymentStatus.
8. HTTP/identidade de serviço, nova role PAYMENT_SERVICE, Flyway em Payment e
   recuperação durável conforme 57–62, sem broker/dependência de PSP/upgrades.

## Validação desta definição

Leitura do código, DTOs, segurança, migrations, workers e testes existentes;
conferidos o esqueleto Payment, payment_db e a impossibilidade atual de desfazer
consumo CONFIRMED. Essa lacuna fundamenta a resolução antes de reembolso.
Não foram executados testes de aplicação, migrations, cobranças, alterações de
Keycloak ou chamadas a PSP nesta tarefa documental. Validar diff/referências e
criar commit local. A aprovação posterior do utilizador foi registrada
documentalmente, sem executar operações financeiras ou alterar o Keycloak.
