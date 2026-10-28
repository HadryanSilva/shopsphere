# Conclusão do carrinho no checkout — proposta

Estado: proposta integral aprovada pelo utilizador em 2026-10-27;
microtarefa 42 concluída. Os contratos e decisões abaixo orientam as entregas
43–48. A aprovação do desenho não representa funcionalidade implementada na
entrega documental 42. A persistência local da etapa 43 foi implementada depois,
conforme [CHECKOUT_PERSISTENCE.md](../cart-service/CHECKOUT_PERSISTENCE.md);
O protocolo HTTP e a identidade da etapa 44 também foram implementados,
conforme [CHECKOUT_API.md](../cart-service/CHECKOUT_API.md);
A persistência da intenção e origem da etapa 45 foi implementada, conforme
[CHECKOUT_PERSISTENCE.md do Order](../order-service/CHECKOUT_PERSISTENCE.md);
A orquestração da etapa 46 foi implementada com configuração temporária para
desenvolvimento, conforme [CHECKOUT_ORCHESTRATION.md](../order-service/CHECKOUT_ORCHESTRATION.md);
as etapas 47–48 permanecem por implementar.

## Estado verificado

- Order recebe endereço e Idempotency-Key, deriva customerId do JWT e procura
  pedido por cliente/chave antes das chamadas externas.
- CreateOrderUseCaseImpl lê GET /api/v1/carts/me, revalida os produtos no Catalog
  e persiste snapshots em transação local, com estado PENDING_STOCK.
- A idempotência compara endereço; não há vínculo persistido ao carrinho.
  Outra chave pode criar outro pedido com os mesmos itens.
- Cart possui ACTIVE, CHECKED_OUT e ABANDONED. Adição, atualização e remoção
  serializam alterações com lock pessimista na raiz; a leitura não conclui o
  carrinho. Alterar apenas filhos não garante incremento de @Version.
- A criação automática depende do índice parcial de um ACTIVE por cliente.
  A segurança atual do Cart autentica /api/v1/carts/** sem distinguir serviço.
- Migrations existentes: Cart V1–V2; Order V1–V3. Qualquer evolução usará novas
  versões, reconfirmadas na implementação; nenhuma migration existente muda.

## Resultado pretendido

Atualização do utilizador em 2026-10-27: o sistema ainda está em desenvolvimento,
sem operação em produção. As próximas entregas priorizam o fluxo final e os testes
locais, sem acrescentar tratamento de dados legados ou procedimentos de rollout
de produção. As referências históricas à compatibilidade descrevem decisões das
entregas anteriores, não requisitos adicionais para as próximas etapas.

Uma intenção de checkout usa um snapshot imutável de productId/quantity e
produz no máximo um pedido por carrinho. Concorrência com edição tem resultado
serializado: a edição entra no snapshot ou é rejeitada após o bloqueio lógico.
Concluir o carrinho significa que o pedido foi persistido; não significa
pagamento aprovado, estoque reservado ou compra confirmada.

Preservar POST /api/v1/orders, endereço, Idempotency-Key, resposta 201/Location,
consulta por proprietário e snapshots monetários recalculados no Catalog.
Não confiar nos preços/totais do Cart nem alterar o contrato público sem
aprovação específica. RabbitMQ, Outbox, Saga, Payment e ligação ao Inventory
ficam fora desta fase.

## Protocolo aprovado

1. Order registra uma intenção durável por cliente/chave, vinculada ao endereço
   original, antes de chamar Cart. Repetições incompatíveis retornam 409.
   A intenção possui identificador estável, distinto da chave pública.
2. Order solicita ao Cart a preparação do checkout. Numa transação curta,
   Cart bloqueia a raiz ACTIVE, valida itens e associa a intenção ao carrinho;
   persiste o snapshot e o bloqueio lógico. Replay da mesma intenção devolve
   o mesmo snapshot, mesmo que o carrinho já esteja concluído.
3. Enquanto houver preparação, GET /me continua a apresentar o carrinho;
   mutações retornam 409 e não criam outro ACTIVE implicitamente. Usar um
   registro de preparação associado ao ACTIVE evita mudar o enum público e
   o índice parcial apenas para representar o bloqueio. Todas as escritas
   verificam esse registro sob o mesmo lock da raiz.
4. Outra intenção/chave concorrente recebe 409 enquanto o carrinho estiver
   associado à primeira. A associação é resolvida sob lock no Cart, não por
   comparação de listas em memória no Order.
5. Order consulta Catalog fora de transação, valida os produtos e grava pedido,
   itens, sourceCartId único e vínculo à intenção numa transação local.
   A unicidade de sourceCartId é a segunda barreira contra duplicação.
6. Após commit do pedido, Order solicita conclusão idempotente ao Cart:
   a preparação passa a concluída e o carrinho a CHECKED_OUT na mesma
   transação local. Os itens históricos são preservados. Uma adição posterior
   sem cartId pode criar outro ACTIVE; referência ao antigo não o reabre.
7. A resposta de criação pode ser 201 após persistência do pedido, mesmo se a
   conclusão remota estiver pendente. Order mantém essa pendência durável;
   replay e reconciliador retomam a conclusão sem recriar o pedido.

## Falhas e recuperação

| Situação | Tratamento proposto |
|---|---|
| Timeout ao preparar | Consultar/repetir pela mesma intenção; não assumir que Cart não gravou. |
| Produto inválido antes do pedido | Marcar intenção como abortada de forma definitiva no Order; depois liberar preparação idempotentemente. |
| Falha transitória no Catalog ou banco | Preservar intenção e snapshot para retomada; não liberar por suposição. |
| Commit do pedido com resposta perdida | Consultar intenção/pedido e concluir Cart; não abortar. |
| Cart indisponível após commit | Guardar conclusão pendente e repetir pelo reconciliador. |
| Processo reiniciado em qualquer etapa | Retomar intenções duráveis, inclusive preparação remota sem confirmação local. |
| Aborto concorrente com persistência | Serializar transições da intenção no Order; estado terminal abortado impede criação tardia de pedido. |

Não expirar nem liberar preparação apenas pelo relógio: um pedido pode ter sido
persistido com resposta perdida. O reconciliador no Order decide pela intenção
durável e retoma trabalho; falhas definitivas liberam somente após impedir
persistência tardia. Um prazo operacional, backoff, tamanho de lote, retenção
e tratamento de intenções irrecuperáveis precisam ser definidos antes do uso.
Não manter transações/locks de banco durante HTTP nem usar transação entre bancos.

## Contratos internos aprovados

Prefixo proposto: /internal/api/v1/cart-checkouts, seguindo a convenção interna
existente no Inventory. Sem rota no Gateway.

| Operação | Contrato candidato |
|---|---|
| Preparar/repetir | PUT /{checkoutId}, body customerId; 201 inicial, 200 replay, snapshot com cartId e itens productId/quantity. |
| Consultar resultado | GET /{checkoutId}; 200 com estado e snapshot, 404 inexistente. |
| Concluir | POST /{checkoutId}/complete, body orderId; 200 inicial/replay equivalente. |
| Abortar | POST /{checkoutId}/abort, body customerId; 200 inicial/replay de aborto, inclusive preparação ainda ausente; 409 se já concluído. |

Preparação vazia ou associação incompatível: 409; body inválido: 400;
carrinho ausente: 404. Completar com outro orderId, concluir abortado ou
reutilizar intenção com outro cliente: 409. Detalhar payloads e erros no
fecho do contrato, preservando os padrões efetivos de cada serviço.

Identidade proposta: client_credentials de shopsphere-order, role dedicada ao
checkout no Cart, validação local de emissor/audiência/azp e vínculo ao cliente
que criou a preparação. customerId interno vem da intenção criada a partir do
JWT autenticado pelo Order; nunca do body público. Token de serviço não substitui
o JWT do utilizador nas APIs /me. Não reutilizar INVENTORY_SERVICE como permissão
de Cart. O desenho de permissões está aprovado; o provisionamento será realizado
na entrega correspondente, sem alterar o Keycloak nesta microtarefa.

## Microtarefas propostas

| Nº | Entrega | Critério de conclusão |
|---|---|---|
| 42 | Fechar regras e contratos Cart–Order | Concluída: proposta integral aprovada em 2026-10-27, incluindo semântica de conclusão, bloqueio/409, 201 com conclusão pendente, identidade, payloads, erros e recuperação. Somente documentação. |
| 43 | Persistir preparação e proteger mutações no Cart | Concluída: V3, snapshot/vínculo duráveis, transições idempotentes e verificações sob lock nas escritas; 100 testes aprovados com PostgreSQL, incluindo concorrência, upgrade, reinício e rollback. Sem endpoints ou integração Order. |
| 44 | Expor protocolo interno e identidade de serviço | Concluída: quatro operações HTTP, JWT/role/azp/audiência, role provisionada e delimitada no Keycloak local; Cart com PostgreSQL e token real: 139 aprovados; Gateway: 86 aprovados. Segurança pública preservada, sem rota interna no Gateway ou integração Order. |
| 45 | Persistir intenção e origem do pedido no Order | Concluída: V4, sourceCartId único nullable para legados, intenção/endereço/snapshot e agenda duráveis; commit conjunto com pedido, aborto serializado e replay. Suíte Order com PostgreSQL: 154 aprovados, incluindo upgrade, reinício, rollback e concorrência. Sem ativação do fluxo novo ou integração HTTP. |
| 46 | Orquestrar snapshot e conclusão no Order | Concluída: protocolo interno com client_credentials, Catalog fora de transações, conclusão/aborto duráveis e replay; contrato público preservado. Suíte Order com PostgreSQL: 196 aprovados, incluindo timeout, duas chaves e resposta perdida após commit. Modo novo disponível por configuração temporária de desenvolvimento. |
| 47 | Recuperar conclusões e abortos pendentes | Concluída: job configurável com identidade de serviço existente, seleção/claim SKIP LOCKED e agenda durável, backoff exponencial e avisos seguros. Reinício nas quatro fases, concorrência e isolamento de falhas validados com PostgreSQL; suíte Order com 210 aprovados, sem falhas ou ignorados. Sem migration nova ou ativação normal do modo snapshot. |
| 48 | Validar fluxo integrado e documentar operação | PostgreSQL, contratos entre serviços, segurança e falhas ambíguas; provar um pedido por carrinho e que pedidos antigos continuam consultáveis. Documentar intervenção e limites. |

Cada entrega inclui testes focados; não deixar os testes de recuperação apenas
para a última etapa. Durante o desenvolvimento, usar a configuração temporária
para validar o fluxo novo por etapas; as etapas 47–48 completam recuperação
automática e testes integrados locais, sem exigir procedimentos de produção.

## Decisões aprovadas

- Adotar conclusão após persistência do pedido, preservando PENDING_STOCK.
- Bloquear edição durante preparação e devolver 409, mantendo leitura /me.
- Aceitar 201 com conclusão do Cart pendente e recuperação durável no Order.
- Adotar HTTP interno com identidade de serviço e permissão própria de Cart.
- Aprovar intenção durável, migrations incrementais e ausência de liberação
  automática por TTL; definir política operacional de retomada/aborto.
- Aprovar rotas, DTOs e erros finais antes de implementar as etapas seguintes.

## Especificação detalhada da microtarefa 42

Os termos normativos desta seção descrevem a proposta aprovada; não afirmam
comportamento já implementado.

### Identidade e payloads

checkoutId, customerId, cartId, productId e orderId são UUIDs. checkoutId é
gerado uma vez pelo Order e não é a Idempotency-Key. O par cliente/chave é
único no Order, independentemente da existência de pedido. Comparação de
endereço mantém a igualdade exata dos seis campos atuais, incluindo a diferença
entre null e string vazia em addressLine2; não adicionar normalização implícita.

Preparação recebe somente `{"customerId":"<uuid>"}`. Cart escolhe o ACTIVE
desse cliente sob lock. Não recebe preços, quantidades, endereço ou chave
pública. Conclusão recebe somente `{"orderId":"<uuid>"}`; aborto recebe
`{"customerId":"<uuid>"}` para permitir registrar aborto antes da preparação.
Consulta não recebe body. Não adicionar header Idempotency-Key interno: a
identidade da operação é checkoutId e o replay verifica os dados associados.

Resposta comum de preparação, consulta, conclusão e aborto:

```json
{
  "checkoutId": "<uuid>",
  "customerId": "<uuid>",
  "cartId": "<uuid ou null>",
  "status": "PREPARED",
  "items": [{"productId": "<uuid>", "quantity": 2}],
  "orderId": null,
  "preparedAt": "2026-10-27T12:00:00Z",
  "completedAt": null,
  "abortedAt": null
}
```

Os marcadores de UUID são ilustrativos. Timestamps são UTC ISO-8601 gerados
pelo Cart; campos nullable são sempre incluídos. items é lista sem duplicados,
ordenada por productId para resposta estável, com quantity entre 1 e
Integer.MAX_VALUE. Não há limite novo de itens nesta fase: o snapshot cobre
todos os itens existentes no carrinho. Paginação/limite exigiria decisão própria.

PREPARED tem cartId/preparedAt preenchidos e orderId/completedAt/abortedAt nulos.
COMPLETED preserva snapshot e preenche orderId/completedAt. ABORTED preserva
snapshot e preenche abortedAt; se o aborto precedeu preparação, cartId e
preparedAt são nulos e items é vazio. Esses registros não podem ser preparados
mais tarde. PUT inicial retorna 201 com Location apontando para a consulta;
replay equivalente retorna 200 com o estado atual e o snapshot original.

### Estados e concorrência no Cart

| Estado atual | Preparar com mesmos dados | Concluir | Abortar |
|---|---|---|---|
| Ausente | Criar PREPARED se ACTIVE não vazio e livre | 404 | Criar ABORTED sem associar carrinho; 200 |
| PREPARED | 200, snapshot original | COMPLETED; 200 | ABORTED; 200 |
| COMPLETED | 200, snapshot original e orderId | 200 mesmo orderId; 409 outro | 409 |
| ABORTED | 409 | 409 | 200 mesmos dados |

GET ausente retorna 404; GET existente retorna 200 em qualquer estado.
CustomerId divergente em PUT/abort retorna 409 para o proprietário do registro.
Cliente de serviço diferente do criador recebe 404 em operações sobre registro
existente, sem obter seu conteúdo; autenticação/permissão é verificada antes.

Cart mantém no máximo uma preparação PREPARED por carrinho. ABORTED libera
somente a associação que lhe pertence, preservando ACTIVE e itens; uma nova
intenção com outra chave pode então preparar o mesmo carrinho. COMPLETED
preserva a associação histórica e muda o carrinho para CHECKED_OUT. Não existe
reabertura por cancelamento de pedido nesta fase.

Unicidade de checkoutId e de preparação ativa deve ser garantida no banco.
PUT e aborto concorrentes para um ID ainda ausente precisam convergir para
um único registro; a transação perdedora relê o resultado após rollback,
sem manter uma transação marcada como falha. Aborto que vence impede PUT
atrasado; PUT que vence é liberado pelo aborto seguinte. Não basta um GET/404
seguido de INSERT sem tratar a corrida.

As transações de preparação/transição e edição adotam ordem consistente de
locks. Edição sempre bloqueia a raiz antes de verificar preparação e alterar
filhos. POST sem cartId verifica bloqueio mesmo no caminho de criação
automática. Se conclusão venceu a disputa e não resta ACTIVE, PATCH/DELETE
mantêm 404 e POST sem cartId pode criar outro carrinho; uma escrita que ainda
encontra ACTIVE preparado retorna 409. DELETE de item ausente só mantém 204
quando o carrinho está livre para edição.

GET /me mantém contrato e enriquecimento atual pelo Catalog; durante preparação
pode continuar falhando pelos erros atuais dessa integração. O checkout interno
não depende desse GET enriquecido. Depois de concluído, GET /me retorna 404
até existir um novo ACTIVE; não retorna o carrinho histórico nem cria um vazio.

### Estados e recuperação no Order

| Estado da intenção | Significado e próximo passo |
|---|---|
| PREPARING | Dados de cliente/chave/endereço duráveis; preparar ou consultar Cart. |
| PREPARED | Snapshot durável; validar Catalog e tentar persistência local. |
| COMPLETION_PENDING | Pedido e vínculo gravados atomicamente; repetir complete. |
| COMPLETED | Cart confirmou conclusão; replay retorna pedido existente. |
| ABORT_PENDING | Decisão terminal de não criar pedido gravada; repetir abort no Cart. |
| ABORTED | Cart confirmou aborto; chave permanece consumida pela intenção falhada. |

Estados da intenção são internos e não ampliam OrderStatus nem o DTO público.
Gravação do pedido e passagem a COMPLETION_PENDING bloqueiam/revalidam a intenção
na mesma transação. ABORT_PENDING impede essa gravação e nunca pode ser escolhido
se já houver pedido. Workers obsoletos revalidam o estado antes de cada commit;
resultado HTTP atrasado não pode regredir estado terminal. Duas execuções da
mesma intenção convergem por locks/unicidade, sem HTTP sob transação.

Order tenta concluir Cart antes de responder, com tempo limitado. Se o pedido
estiver persistido, retorna 201 com o mesmo body/Location atual mesmo quando
complete falha; a pendência é interna. Replay de chave com pedido existente
mantém 201 e não depende de disponibilidade do Cart para responder.

Antes de existir pedido: carrinho ausente retorna 404; vazio/ocupado ou produto
incompatível retorna 409. A intenção grava o código e uma razão segura de falha
de negócio; aborto é confirmado mesmo se nunca houve preparação. Replay de
intenção abortada repete a falha registrada, não escolhe outro carrinho.
Para tentar novamente após corrigir itens, usar nova chave depois da liberação.

Timeout/conexão mantém 503 e falha de integração mantém 502 conforme o padrão
atual. Esses resultados são ambíguos: a intenção permanece recuperável e o
reconciliador pode criar o pedido posteriormente usando o mesmo snapshot e
endereço. Essa semântica foi explicitamente aprovada na microtarefa 42. Não introduzir 202 ou
endpoint público de intenção nesta fase. O cliente repete POST com a mesma
chave para consultar/retomar; nova chave não resolve uma preparação pendente.

Catalog continua sendo fonte de preço/nome/moeda no momento da criação do
pedido. Antes do commit, uma retomada pode obter preços atualizados; depois
dele, os snapshots financeiros são definitivos e não são recalculados no replay.

### Segurança, erros e parâmetros operacionais

Permissão proposta: realm role CART_CHECKOUT_SERVICE mais azp shopsphere-order,
com issuer, assinatura, validade e audience shopsphere-api validados no Cart.
A identidade persistida é o azp validado. ADMIN isoladamente e token de cliente
web não autorizam operações internas. O cliente shopsphere-order já existente
receberá apenas a role adicional, sem alterar permissões do Inventory.
Validar audiência nas novas rotas internas preservando a aceitação atual das
rotas públicas; endurecimento global do Cart requer decisão separada.

As quatro combinações de verbo/caminho internas definidas são a allowlist;
outras são negadas.
401/403 internos usam Problem Details e preservam WWW-Authenticate, sem mudar
handlers públicos. Erros MVC seguem [api-errors.md](api-errors.md), sem adicionar
campos novos; 409 de Cart é tratado explicitamente por Order, não como 502.
401/403 downstream indica falha de configuração/credenciais e não deve ser
devolvido como falha de autenticação do utilizador. Registrar categoria segura,
manter intenção para recuperação e devolver 502 se não há pedido persistido.

Proposta operacional configurável, sem dependências novas:

- HTTP interno: conexão 2000 ms e leitura 5000 ms; sem retry automático no
  cliente. Repetições explícitas usam checkoutId e operações idempotentes.
- Varredura no Order a cada 60 segundos, lote de 50 intenções elegíveis;
  backoff por intenção de 60 segundos, dobrando até 15 minutos.
- Persistir próxima tentativa, número de tentativas e categoria segura da
  última falha. Sem guardar tokens, endereço em logs ou mensagens downstream.
- Não abandonar intenções por número de tentativas. Após 15 minutos pendentes,
  emitir aviso operacional e continuar tentativas; sem liberação por relógio.
- Selecionar apenas intenções elegíveis em lotes limitados; concorrência entre
  workers não pode duplicar efeitos nem impedir progresso das demais.
- Sem purge automático nesta fase: intenção, aborto antecipado e snapshot são
  necessários para impedir replay tardio. Retenção futura exigirá protocolo próprio.
- Operação manual: restabelecer dependências/credenciais e retomar o worker;
  investigar por checkoutId. Não editar SQL, desbloquear ou apagar registros
  como procedimento normal. Endpoint de intervenção fica fora desta fase.

### Persistência, compatibilidade e ativação

Cart precisa de registros locais de preparação e itens do snapshot, vínculo ao
cliente de serviço e constraints de estados/associação. Order precisa de
intenções, snapshot recuperável, endereço, falha segura, agenda de tentativas
e vínculo único de origem no pedido. Nomes SQL e índices serão fechados nas
microtarefas de persistência a partir deste modelo; não executar DDL na 42.
Sem FK entre serviços. Dados legados mantêm origem nullable e replay atual.

Pedidos legados sem sourceCartId não permitem provar que seu carrinho atual
já foi usado. A garantia de um pedido por carrinho vale para o novo protocolo;
não fabricar histórico nem inferir origem por igualdade de itens.

O sistema está em desenvolvimento: não se exige janela de manutenção, drenagem
ou rollout de produção. A configuração temporária da etapa 46 permite testar o
novo fluxo durante a implementação incremental. Testar uma configuração de
checkout por execução, usando schemas de teste isolados. As etapas 47–48 completam
a recuperação automática e a validação integrada; não introduzir compatibilidade
adicional com dados legados como requisito dessas entregas.

### Critérios de aceitação a transportar para 43–48

- Edição versus preparação, duas chaves sobre o mesmo ACTIVE e replay da mesma
  chave sob concorrência com PostgreSQL: snapshot consistente e no máximo um pedido.
- Aborto antes de PUT atrasado; aborto versus commit; complete repetido com mesmo
  e outro orderId; criação de novo ACTIVE após conclusão; chave abortada não reutilizada.
- Falha após cada commit e antes/depois de cada HTTP, incluindo resposta perdida;
  reinício recupera sem token do utilizador e sem repetir efeitos de negócio.
- Replay legado, endereço incompatível, timestamps/snapshot estáveis, erros públicos,
  isolamento de serviço e ausência de rotas internas pelo Gateway.
- Lotes com falhas não impedem progresso, dependência indisponível mantém segurança
  e restauração permite conclusão/aborto sem desbloqueio manual de dados.

## Fecho da microtarefa 42

Especificação documental aprovada integralmente pelo utilizador em 2026-10-27,
incluindo criação posterior a 502/503, bloqueio sem TTL, role dedicada,
contratos internos e estratégia de ativação com drenagem. Microtarefa 42
concluída. As entregas 43–47 foram concluídas posteriormente; 48 permanece por implementar.
A orientação posterior do utilizador substitui o planejamento de janela
operacional por desenvolvimento e validação local, conforme registrado acima.

Validação desta revisão: leitura das implementações de idempotência e mutação,
contratos de erros e migrations existentes; nenhum teste de aplicação executado,
nenhum endpoint/schema/configuração alterado. Verificar whitespace e consistência
documental antes da entrega não substitui os testes das futuras implementações.
