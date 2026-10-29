# Order–Inventory — proposta da próxima entrega (microtarefa 49)

**Estado: proposta integral aprovada explicitamente pelo utilizador; microtarefa 49 concluída.**
A aprovação confirma as sete decisões ao final e a sequência incremental
50–55. O documento descreve o comportamento a implementar; não comprova que
API, schema ou integração já existam. Executar uma microtarefa por vez,
limitando mudanças ao seu escopo. O registro desta aprovação é documental.

## Base verificada na definição da microtarefa 49

| Área | Implementação existente |
|---|---|
| Checkout Order | Snapshot do Cart, revalidação Catalog, intenção durável e commit do pedido em PENDING_STOCK; sourceCartId único. |
| Conclusão Cart | Depois do commit do pedido, com recuperação automática; COMPLETED da intenção indica conclusão do Cart, não estoque. |
| API pública Order | POST /api/v1/orders com endereço/chave e 201/Location; GET por proprietário; não há endpoint público de cancelamento/pagamento. |
| Estados Order | PENDING_STOCK, PENDING_PAYMENT, CANCELLED e CONFIRMED já existem no enum/schema; o fluxo atual não os vincula ao Inventory. |
| Reserva Inventory | PUT/GET por orderId; 1–100 produtos distintos, inteiros positivos, todos ou nenhum; replay não renova prazo nem reativa terminal. |
| Ciclo Inventory | ACTIVE, CONFIRMED, RELEASED, EXPIRED; TTL padrão PT15M e job de expiração PT60S. Confirmar consome físico/reservado; liberar/expirar devolve somente reservado. |
| Segurança interna | Inventory exige INVENTORY_SERVICE e azp shopsphere-order, JWT/audiência; Cart exige CART_CHECKOUT_SERVICE com o mesmo azp. |
| Limites atuais | Checkout não tem teto de 100 produtos; release inexistente retorna 404 e não impede um PUT posterior. Falha na criação de reserva não consome orderId. |
| Migrations | Order V1–V4 e Inventory V1–V4; próximas versões livres são V5 em ambos, a reconfirmar antes de implementar. |

Fontes: [contrato Cart–Order](cart-checkout-proposal.md),
[operação integrada](../order-service/CHECKOUT_OPERATION.md),
[criação de reservas](../inventory-service/RESERVATIONS.md),
[transições](../inventory-service/RESERVATION_TRANSITIONS.md) e
[expiração](../inventory-service/EXPIRATION.md), conferidas contra controllers,
casos de uso, modelos, configuração e testes existentes.

## Escopo recomendado

Integrar reservas ao checkout por HTTP interno, com identidade de serviço,
trabalho durável e recuperação no Order. Usar o pedido e seus itens persistidos
como intenção imutável de estoque; não consultar Cart/Catalog novamente para
montar a reserva. Não alterar snapshots financeiros depois do commit.

Integrar o fluxo por snapshot das etapas 42–48, não o caso de uso anterior.
O modo snapshot continua false por padrão hoje; esta definição não altera sua
seleção. O worker seleciona trabalho de estoque explicitamente gravado com o
pedido, não qualquer registro que tenha status PENDING_STOCK.

Nesta fase, reservar, acompanhar expiração e cancelar com compensação segura.
Não chamar confirm automaticamente: consumo definitivo depende da política de
pagamento, que não está definida aqui. Payment, eventos, RabbitMQ, Outbox,
novos serviços e cancelamento público ficam fora desta proposta.

As consequências visíveis propostas são: limite de 100 produtos no checkout,
avanço automático para PENDING_PAYMENT quando reservado e cancelamento posterior
por rejeição/expiração. Nenhuma dessas regras deve ser introduzida como se já
fizesse parte do contrato público vigente.

## Sequência proposta e relação com Cart

1. Manter preparação/snapshot do Cart e validação comercial no Catalog.
2. Antes de persistir pedido, rejeitar snapshot com mais de 100 productIds
   distintos: 409, falha segura durável na intenção e aborto do Cart pelo
   protocolo existente. Não consultar Inventory, criar pedido ou dividir reserva.
   Cart continua permitindo editar/ler carrinhos maiores; o limite é do checkout.
3. Gravar pedido PENDING_STOCK, itens, origem, COMPLETION_PENDING e trabalho
   de estoque WAITING_CART no mesmo commit local. Rollback não deixa trabalho
   sem pedido nem pedido novo deste fluxo sem recuperação de estoque.
4. Concluir Cart exatamente pelo protocolo atual, com replay/worker. Não abortar
   Cart depois de existir pedido, mesmo se posteriormente faltar estoque.
5. Um worker de estoque só inicia PUT quando a intenção do Cart estiver
   COMPLETED. Isso evita gastar o TTL da reserva enquanto Cart está bloqueado.
   Conclusão do Cart e elegibilidade do trabalho devem ser recuperáveis após
   falha entre commits; não depender de callback ou sinal somente em memória.
6. Reservar pelo orderId e pelos productId/quantity do pedido. Nunca usar
   checkoutId ou Idempotency-Key pública como identidade da reserva.
7. Registrar a resposta validada em transação local curta, revalidando estado,
   intenção e decisão de cancelamento antes de qualquer transição.

O POST inicial continua devolvendo o pedido persistido com 201/Location e
PENDING_STOCK, sem depender de Inventory para responder. Não enviar 502/503
ao utilizador por falha de Inventory depois desse commit. Falhas anteriores
ao pedido preservam as regras do checkout atual.

GET e replay da chave devolvem o estado atual do pedido: status/updatedAt podem
avançar; ID, origem, endereço, itens e valores financeiros são estáveis.
PENDING_PAYMENT/CANCELLED após 201 não significam criação de outro pedido.
Manter o formato de OrderResponse e paymentStatus nulo, sem campos novos nesta
fase. Nova compra exige novo ACTIVE e nova chave; nunca reabrir o Cart histórico
nem reaproveitar uma reserva terminal com outro payload.

## Contratos HTTP utilizados

Prefixo existente: /internal/api/v1/inventory/reservations.

| Operação | Requisição | Resposta existente |
|---|---|---|
| Reservar | PUT /{orderId}, body items | 201 inicial ou 200 replay, Location e reserva. |
| Consultar | GET /{orderId}, sem body | 200 reserva; 404 ausente/inacessível. |
| Liberar existente | POST /{orderId}/release, sem body | 200 RELEASED/EXPIRED; 404 ausente; 409 CONFIRMED. |
| Confirmar consumo | POST /{orderId}/confirm, sem body | Existe, mas não será acionado nesta fase. |

Exemplo de intenção enviada pelo Order, sempre a partir de itens persistidos:

```json
{"items":[{"productId":"00000000-0000-0000-0000-000000000001","quantity":2}]}
```

Validar a reserva recebida: orderId esperado, estado conhecido, todos os itens
e quantidades exatamente equivalentes, sem duplicados/extras/ausências, datas
UTC coerentes (createdAt < expiresAt e updatedAt >= createdAt). Ordenação do
JSON não altera intenção. Comparar o prazo/data de criação já observados nas
retomadas; replay não pode renovar validade. Resposta inválida é falha técnica,
não prova de falta de estoque ou autorização para cancelar outra reserva.

## Lacuna de segurança: aborto antes de reserva

Somente GET + release atuais não encerram uma operação ambígua com segurança:
um PUT pode ter sofrido timeout e ainda estar aguardando no Inventory. GET/404
e release/404 não impedem esse PUT de criar ACTIVE posteriormente. Um contador
de tentativas ou lease no Order também não cancela HTTP já enviado.

Recomendação: acrescentar uma operação interna de aborto durável no Inventory.
É uma **extensão de contrato aprovada para implementação**, não um endpoint existente:

```http
POST /internal/api/v1/inventory/reservations/{orderId}/abort
Content-Type: application/json

{"items":[{"productId":"00000000-0000-0000-0000-000000000001","quantity":2}]}
```

Body usa a mesma intenção de 1–100 itens. Não recebe customerId, identidade,
preços, deadline ou chave adicional. Em sucesso inicial/replay equivalente:

```json
{"orderId":"00000000-0000-0000-0000-000000000002","status":"ABORTED","reservation":null}
```

Se havia reserva, reservation contém a representação existente após liberação,
com estado RELEASED ou EXPIRED. ABORTED é resultado desta operação, **não** um
quinto estado acrescentado ao enum da reserva. Resposta sempre inclui
reservation, explicitamente null quando a reserva nunca existiu.

| Situação no Inventory | Resultado proposto do aborto |
|---|---|
| Sem reserva | Gravar impedimento durável por orderId/cliente/itens; retornar 200 ABORTED/null. Não exigir posições de estoque existentes. |
| ACTIVE equivalente | Gravar impedimento e liberar atomicamente; 200 ABORTED com RELEASED ou EXPIRED conforme prazo no Inventory. |
| RELEASED/EXPIRED equivalente | Gravar/confirmar impedimento; 200 ABORTED com reserva terminal, sem devolver novamente. |
| CONFIRMED | 409, sem desfazer consumo, escrever impedimento ou afirmar aborto. |
| Intenção diferente | 409, sem sobrescrever a intenção/reserva. |
| Registro de outro cliente | 404, sem revelar conteúdo. |

PUT posterior ao impedimento retorna 409 e nunca cria/reativa reserva, mesmo
com itens iguais; GET da reserva continua 404 se ela nunca existiu. O replay
de abort confirma a barreira; GET/404 isolado nunca substitui essa confirmação.
Payload inválido retorna 400; erros 401/403 seguem segurança interna existente.
Não alterar o significado dos endpoints confirm/release/GET já existentes.

PUT e abort devem serializar a mesma identidade no Inventory, inclusive quando
nenhuma reserva existe. Um registro local de coordenação/impedimento é proposto,
sem FK para posições (aborto de produto sem estoque precisa funcionar).
Bloquear coordenação, depois reserva e depois posições na ordem UUID existente.
Aborto que vence bloqueia PUT atrasado; PUT que vence é liberado pelo aborto.
Barreira e devolução fazem commit juntos; rollback não confirma aborto parcial.
Expiração/confirm/release não podem introduzir aquisição de locks em ordem inversa.
Não prometer essa proteção com um SELECT ausente seguido de INSERT sem tratar corrida.

Nomes SQL/constraints foram fechados na microtarefa 50, conforme
[ABORT_PERSISTENCE.md](../inventory-service/ABORT_PERSISTENCE.md). A definição
49 não executou DDL. Preservar impedimentos sem purge automático, pois
removê-los permitiria um PUT tardio.

## Estados e decisões no Order

O trabalho durável de estoque é separado de CheckoutIntentStatus. COMPLETED da
intenção continua significando Cart concluído e não deve impedir a agenda de
estoque. Nomes abaixo são candidatos internos, ainda não implementados:

| Trabalho | Comportamento e efeito público proposto |
|---|---|
| WAITING_CART | Pedido PENDING_STOCK; aguardar conclusão durável do Cart. |
| RESERVE_PENDING | Consultar/repetir reserva, sem mudar orderId/itens. Indisponibilidade mantém PENDING_STOCK. |
| RESERVED | ACTIVE equivalente observado; PENDING_PAYMENT, guardar expiresAt e continuar monitorando. |
| ABORT_PENDING | Decisão terminal de cancelar registrada localmente; impedir novos PUTs por workers locais e repetir abort até prova remota. |
| CLOSED | Sem trabalho adicional, com razão segura e prova de aborto/RELEASED/EXPIRED; pedido CANCELLED. |
| REVIEW_REQUIRED | Inconsistência de identidade/itens/datas, CONFIRMED inesperado ou conflito não resolvido; alertar e reconciliar sem efeitos destrutivos. Não fingir sucesso/cancelamento. |

ABORT_PENDING mantém o estado público anterior até a confirmação segura; o
trabalho interno já impede retorná-lo a RESERVED. Uma resposta ACTIVE atrasada
não desfaz essa decisão. Só marcar CANCELLED e CLOSED juntos após prova válida
de que não haverá reserva ativa tardia. A reserva terminal RELEASED/EXPIRED já
impede reativação por PUT no contrato existente e serve como essa prova.

PENDING_PAYMENT significa que a última reconciliação observou reserva ACTIVE;
não comprova pagamento nem disponibilidade em tempo real. Sem Payment nesta
fase, a reserva pode expirar e o pedido ser cancelado depois. Nunca chamar
Order.confirm() por estoque reservado nem tratar Inventory CONFIRMED como
pagamento aprovado. CONFIRMED inesperado exige revisão de política futura.

## Matriz de falhas e recuperação recomendada

| Observação | Próximo passo proposto |
|---|---|
| PUT 201/200 ACTIVE equivalente | Registrar RESERVED/PENDING_PAYMENT e agenda de monitoramento. |
| Timeout/conexão/5xx ou resposta perdida | Preservar pendência; consultar GET/repetir PUT com mesmo orderId e payload. GET/404 aqui não autoriza cancelamento por suposição. |
| PUT 404/409 | Consultar GET antes de decidir: outra execução pode já ter criado a reserva. Não analisar strings de detail para distinguir falta de saldo de conflito técnico. |
| Após rejeição 404/409, GET encontra ACTIVE equivalente | Aceitar o resultado vencedor, salvo decisão local ABORT_PENDING já gravada. |
| Após rejeição 404/409, GET retorna 404 | Proposta de política: registrar ABORT_PENDING com razão genérica RESERVATION_REJECTED; chamar abort durável antes de CANCELLED. Não chamar isso de saldo insuficiente comprovado. |
| GET/PUT devolve RELEASED/EXPIRED equivalente | Fechar trabalho e cancelar pedido; não tentar criar nova reserva para o mesmo pedido. |
| GET devolve ACTIVE com prazo aparentemente vencido | Não concluir expiração pelo relógio do Order. Solicitar abort, que decide/libera pelo relógio e locks do Inventory; confirmar prova antes de CANCELLED. |
| Inventory/token endpoint 401/403 | Manter a fase e registrar categoria segura de configuração, invalidando token em cache quando aplicável; backoff e nova tentativa após restauração das credenciais. Não devolver 401/403 do serviço ao utilizador nem cancelar. |
| 400 inesperado ou payload incompatível | REVIEW_REQUIRED com decisão anterior preservada, sem mutações incompatíveis; não tratar como falta de estoque. |
| Abort timeout/5xx ou resposta perdida | Continuar ABORT_PENDING; repetir abort equivalente. GET/404 não basta, pois a reserva pode nunca ter existido. |
| Abort 409 por CONFIRMED/intenção incompatível | Revisão, sem alegar liberação nem incrementar estoque. Não repetir PUT nem promover pedido a CONFIRMED. |
| Falha no commit local depois de sucesso remoto | Retomar por GET/replay idempotente; não repetir o efeito com outro orderId. |
| Processo interrompido depois do commit do pedido/Cart | Trabalho WAITING_CART/RESERVE_PENDING já existe e fica novamente elegível. |

Rejeição genérica 409 seguida de ausência pode incluir conflito técnico; a
política proposta prefere encerrar com compensação segura a esperar reposição
indefinidamente. Esse comportamento recebeu aprovação explícita na 49. Se for
necessário diferenciar insuficiência de concorrência para o produto, negociar
códigos de erro estáveis numa revisão do contrato; não interpretar mensagens.

Não oferecer backorder, reservas parciais, renovação de TTL ou tentativas com
novo orderId para contornar expiração. Reposição posterior não ressuscita um
pedido já cancelado. Dados financeiros não são revalidados durante recuperação.

## Segurança, transporte e operação

- HTTP interno direto, host configurável; candidato services.inventory.url no
  Order, que hoje só existe no Gateway. Base local Inventory: porta 8084.
- Reutilizar a identidade shopsphere-order/client_credentials, com permissão
  INVENTORY_SERVICE. Não reutilizar CART_CHECKOUT_SERVICE como autorização de
  Inventory nem encaminhar o JWT do utilizador. Revalidar configuração real do
  Keycloak antes dos testes com token real; não provisionar nada nesta tarefa.
- Novo abort deve ter a mesma política interna de assinatura/issuer/validade,
  audiência shopsphere-api, role e azp. Nenhuma rota interna nova no Gateway.
- Reutilizar a stack RestClient existente; conexão 2000 ms e leitura 5000 ms
  configuráveis, sem retry automático dentro do cliente HTTP. Repetições ficam
  explícitas no worker e usam a mesma intenção. Sem dependência nova por padrão.
- Trabalho persistido com unicidade por pedido, fase/decisão, intenção imutável,
  observação remota/prazo, razão segura, contador, categoria e próxima tentativa.
  Novas migrations incrementais quando aprovadas; nenhuma FK entre serviços.
- Agenda proposta: varredura PT60S, lote 50, backoff PT60S dobrando até PT15M;
  monitorar RESERVED pelo menos a cada PT60S. Parâmetros positivos/configuráveis.
  O prazo recebido ajuda a antecipar a próxima consulta, não autoriza expiração
  local. Sem abandono por contagem nem liberação por relógio isolado.
- Claim/locks locais curtos com SKIP LOCKED, sem HTTP sob transação. Lease
  reduz sobreposição, mas não prova exclusividade de chamadas remotas; transições
  revalidam estado e aborto durável protege contra operações já em voo.
- Após 15 minutos pendentes, aviso operacional e continuidade da reconciliação.
  REVIEW_REQUIRED permite consultas e avisos limitados; não executar mutações
  incompatíveis automaticamente. Restaurar dependências/credenciais e retomar.
  Conflitos de intenção/consumo exigem análise; nenhum endpoint manual novo ou
  edição de SQL é proposto como procedimento normal.
- Logs só com orderId/checkoutId, fase, categoria, contador e resultado seguro;
  sem endereço, chave pública, JWT, secrets, payloads ou texto downstream.

## Microtarefas por dependência

| Nº | Entrega | Critério de conclusão |
|---|---|---|
| 49 | Definir e aprovar contrato Order–Inventory | Concluída: proposta integral e sete decisões aprovadas explicitamente pelo utilizador; sem implementação nesta entrega documental. |
| 50 | Persistir coordenação e aborto de reserva no Inventory | Concluída: V5 e coordenação por pedido/cliente/itens, aborto local atômico e serialização com PUT. Rollback, reinício, 100 itens e disputas com criação/transições validados com PostgreSQL; suíte com 279 aprovados e um token real opcional ignorado. Sem endpoint ou ligação ao Order. |
| 51 | Expor aborto durável no Inventory | Endpoint/DTO/allowlist internos aprovados, replay/erros, JWT e ausência de rota no Gateway; contratos existentes preservados. |
| 52 | Persistir trabalho de estoque e transições no Order | Commit atômico com pedido, fases/decisão/agenda, unicidade e estado público; rollback/reinício/corridas com PostgreSQL. |
| 53 | Implementar cliente e fluxo Order–Inventory | Limite de checkout com aborto durável do Cart, cliente interno, validações e matriz de recuperação; HTTP fora de transações. |
| 54 | Agendar reserva, monitoramento e compensação | Lotes/backoff/claims, cooperação com conclusão Cart, expiração e alertas; retomada sem JWT público e sem duplicar efeitos. |
| 55 | Validar integração e documentar operação | JARs reais incluindo Inventory, schemas isolados, falhas após cada commit/HTTP e respostas perdidas; tokens reais quando disponíveis, limitações explícitas. |

Os números são proposta, a reconfirmar no início de cada entrega. Não executar
linhas em lote. Não implementar funcionalidades só para preencher o plano.
Cada entrega inclui seus testes e commit local; não adiar persistência e
concorrência para a última etapa.

## Critérios de aceitação transversais

- Exatamente um pedido por origem e uma intenção de reserva por orderId;
  replay concorrente não duplica reservado, devolução ou pedido.
- Carrinhos com 100 produtos passam pelo protocolo atômico; com 101 recebem
  409 antes de pedido/Inventory e têm preparação liberada pelo Cart.
- Última unidade disputada por dois pedidos, nenhum resultado parcial e
  rejeição persistida/compensada sem cancelar uma reserva incompatível.
- PUT atrasado antes/depois de abort; timeout de PUT, GET/404, decisão local
  terminal e resposta de abort perdida: nenhuma reserva pode surgir após
  comprovação de cancelamento. Testar pausas maiores que o lease e vários workers.
- Expiração entre resposta e commit no Order, ACTIVE vencida ainda não processada
  pelo job, relógios com diferença e Inventory indisponível: nenhuma confirmação
  por suposição e eventual cancelamento após prova remota.
- Pedido já commitado continua consultável e retorna 201 no replay durante
  indisponibilidade de Inventory. Status atual pode mudar; valores não mudam.
- Cart permanece concluído após rejeição/expiração; uma nova compra usa novo
  ACTIVE, chave e pedido. Não restaurar itens automaticamente nem reabrir origem.
- Reinício após cada fronteira local/remota, inclusive pedido salvo sem Cart
  concluído, reserva criada sem observação local e aborto salvo sem resposta.
- Falhas de identidade/credenciais, body inválido e CONFIRMED inesperado não
  viram sucesso de negócio; logs seguros e demais intenções continuam progredindo.

## Decisões aprovadas pelo utilizador

1. HTTP interno com a identidade existente, sem broker/Outbox/Payment nesta fase.
2. Persistir pedido primeiro e concluir Cart antes de reservar; manter 201
   PENDING_STOCK e recuperação assíncrona mesmo se depois não houver estoque.
3. Limitar checkout a 100 produtos distintos com 409 e aborto do Cart, sem
   limitar a edição do carrinho nem dividir a reserva.
4. ACTIVE observado leva a PENDING_PAYMENT; rejeição/expiração comprovada leva
   a CANCELLED. Sem consumo definitivo/confirm ou reabertura automática do Cart.
5. Acrescentar abort durável no Inventory e a persistência necessária para
   impedir PUT tardio, preservando os endpoints e estados de reserva atuais.
6. Após PUT 404/409 e GET/404, decidir cancelamento genérico, que só termina
   após prova de abort; não classificar erro pela mensagem de Problem Details.
7. Aprovar retenção dos impedimentos, fases/reconciliação e parâmetros propostos,
   sem abandono por prazo/tentativas, além da sequência incremental 50–55.

Alternativas que mudariam este desenho: reservar antes do pedido (exige
identidade de pedido pré-alocada e compensação antes do commit), ampliar o
limite do Inventory (novo contrato de capacidade/concorrência), ou encerrar
reservas por pagamento (requer contrato Payment e consumo/compensação próprios).
Nenhuma alternativa foi escolhida ou implementada implicitamente.

## Validação desta definição

Leitura do código e testes existentes de checkout, persistência de intenção,
reserva/transições/expiração, segurança, migrations e DTOs. A ausência de
impedimento para PUT tardio foi identificada no contrato e nos casos de uso,
não demonstrada por um novo teste executado nesta entrega documental.
Não foram executados testes de aplicação, migrations ou chamadas a serviços.
Validar diff, referências locais e consistência dos caminhos antes do commit.
