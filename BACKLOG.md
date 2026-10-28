# Microtarefas após a revisão geral

Ordem por impacto e dependências. A lista planeia as próximas entregas; não
autoriza executar todas de uma vez. Cada microtarefa deve manter um diff próprio
e testes focados, sem incluir refatorações ou alterações de infraestrutura.

Estado: microtarefas 23 a 28 concluídas. Na microtarefa 23, os testes focados de validação e a suíte
completa do Catalog passaram (`.\gradlew.bat test --offline --no-daemon`):
83 testes aprovados e 1 teste PostgreSQL ignorado por falta da variável de
ativação. Na microtarefa 24, os testes focados de roteamento/segurança passaram
e a suíte completa do Gateway (`.\gradlew.bat test --offline --no-daemon`)
aprovou os 24 testes, sem ignorados. Na microtarefa 25, a suíte completa de
Order com PostgreSQL local aprovou os 131 testes, sem falhas ou ignorados.
Pedidos inexistentes e de outro cliente retornam 404, inclusive para ADMIN,
seguindo o padrão de propriedade do Cart. Na microtarefa 26, overflow na soma
de quantidades passou a retornar 409 sem alterar o item; a suíte completa do
Cart com PostgreSQL local aprovou os 67 testes, sem falhas ou ignorados.
Na microtarefa 27, a leitura passou a usar um batch por carrinho não vazio,
com timeouts de 2000/5000 ms e sem retry automático. A suíte completa do Cart
com PostgreSQL aprovou os 76 testes; os 7 testes de contrato batch do Catalog
e os 2 testes do cliente Catalog do Order também passaram.
Na microtarefa 28, o Location da categoria passou a identificar o recurso
criado; os testes focados e a suíte completa do Catalog com PostgreSQL local
aprovaram os 85 testes, sem falhas ou ignorados. A lista de correções abaixo
está concluída; permanece a definição do protocolo de checkout descrita adiante.

| Ordem | Microtarefa | Prioridade | Escopo e critério de conclusão |
|---|---|---|---|
| 1 | 23 — Restringir criação de produtos a EUR | P1 | Decisão confirmada pelo utilizador: rejeitar USD/GBP/CHF com 400 antes do caso de uso; manter EUR e testar o contrato. Sem conversão de dados existentes. |
| 2 | 24 — Encaminhar categorias pelo Gateway | P2 | Acrescentar encaminhamento para o Catalog, preservando permissões. Testar o roteamento real de GET/POST, além da segurança. |
| 3 | 25 — Consultar pedido por ID | P2 | Implementar a consulta indicada pelo Location do POST. Definir antes o comportamento de recurso inexistente/de outro cliente; testar autenticação e isolamento por proprietário. |
| 4 | 26 — Tratar overflow de quantidade no carrinho | P2 | Concluída: soma acima de Integer.MAX_VALUE retorna 409, preservando a quantidade anterior. Testes de limites, HTTP e persistência aprovados. |
| 5 | 27 — Reduzir chamadas ao Catalog na leitura do carrinho | P2 | Concluída: batch único, sem chamada para carrinho vazio, timeouts 2000/5000 ms e sem retry; preservados payload e erros 404/502/503. Contratos e persistência aprovados. |
| 6 | 28 — Corrigir Location da categoria criada | P3 | Concluída: Location aponta para /api/v1/categories/{id}; teste verifica o ID no header e segue o endereço com GET. |

## Pendência maior de checkout

Planejamento iniciado em 2026-10-27: proposta e microtarefas **42–48** em
[contracts/cart-checkout-proposal.md](contracts/cart-checkout-proposal.md).
A microtarefa **42 — Fechar regras e contratos Cart–Order** foi concluída:
proposta integral aprovada pelo utilizador em 2026-10-27, com contratos HTTP,
payloads, estados, segurança, recuperação e critérios de aceitação.
O desenho aprovado define snapshot persistido, bloqueio de mutações, vínculo
único carrinho/pedido e recuperação durável de conclusão/aborto. Nenhuma API,
regra de segurança ou migration foi alterada na entrega documental 42.

A microtarefa **43 — Persistir preparação e proteger mutações no Cart** foi
concluída: V3 acrescenta preparação/snapshot, transições locais idempotentes e
aborto antecipado; as escritas verificam o bloqueio sob lock da raiz.
Upgrade V2, reinício, replay, concorrência, constraints e rollback foram
validados com PostgreSQL. Suíte completa do Cart: 100 testes aprovados,
sem falhas ou ignorados. V3 aplicada somente em schemas temporários de teste;
V1/V2 preservadas. Sem endpoints internos, mudanças de segurança ou integração
com Order. Detalhes em [cart-service/CHECKOUT_PERSISTENCE.md](cart-service/CHECKOUT_PERSISTENCE.md).
A microtarefa **44 — Expor protocolo interno e identidade de serviço** foi
concluída: PUT/GET de preparação/consulta e POSTs de conclusão/aborto, com
JWT/role CART_CHECKOUT_SERVICE/azp shopsphere-order e audiência interna obrigatória.
A role foi adicionada e delimitada no Keycloak local preservando INVENTORY_SERVICE.
Suíte Cart com PostgreSQL e token real: 139 aprovados; Gateway: 86 aprovados,
sem falhas ou ignorados. Rotas internas sem encaminhamento; segurança pública
preservada, sem migration nova ou integração Order.
Detalhes em [cart-service/CHECKOUT_API.md](cart-service/CHECKOUT_API.md).
A microtarefa **45 — Persistir intenção e origem do pedido no Order** foi
concluída: V4, intenção única por cliente/chave, endereço e snapshot duráveis,
sourceCartId único e nullable para legados, e gravação atômica do pedido com
COMPLETION_PENDING. Aborto serializado impede gravação tardia; agenda e falhas
seguras ficam persistidas para a recuperação futura. Upgrade, reinício,
rollback e concorrência validados com PostgreSQL: suíte completa do Order
com 154 aprovados, sem falhas ou ignorados. V4 somente em schemas temporários;
V1–V3 intactas, sem ativação do fluxo novo, endpoint ou integração HTTP.
Detalhes em [order-service/CHECKOUT_PERSISTENCE.md](order-service/CHECKOUT_PERSISTENCE.md).
A microtarefa **46 — Orquestrar snapshot e conclusão no Order** foi concluída:
novo caso de uso, cliente interno e client_credentials, com preparação,
revalidação Catalog, commit local, conclusão/aborto e retomada por replay.
Falha de conclusão após commit mantém pedido e pendência; nenhuma chamada HTTP
mantém transação aberta. Suíte completa Order com PostgreSQL: 196 aprovados,
sem falhas ou ignorados. Sem migration nova, alteração de contrato público ou
ativação no ambiente normal. Configuração temporária permite testar o modo novo.
Detalhes em [order-service/CHECKOUT_ORCHESTRATION.md](order-service/CHECKOUT_ORCHESTRATION.md).
A microtarefa **47 — Recuperar conclusões e abortos pendentes** foi concluída:
job configurável retoma as quatro fases não terminais, sem JWT do utilizador,
com seleção/claim SKIP LOCKED, agenda durável, backoff exponencial até 15 minutos
e logs seguros. Reinício, disputa entre workers, isolamento de falhas e
recuperação após falha de banco validados com PostgreSQL. Suíte completa Order:
210 aprovados, sem falhas ou ignorados. Sem nova migration ou ativação do modo
snapshot no ambiente normal. Detalhes em
[order-service/CHECKOUT_RECOVERY.md](order-service/CHECKOUT_RECOVERY.md).
A microtarefa 48 permanece por implementar.

Orientação do utilizador em 2026-10-27: o sistema está em desenvolvimento e
ainda não opera em produção. As próximas etapas não exigem tratamento adicional
de dados legados, janelas de manutenção, drenagem ou rollout de produção;
priorizar recuperação automática e validação integrada local do fluxo final.

Antes de considerar o fluxo pronto para compras reais, implementar o protocolo
aprovado de snapshot/conclusão do carrinho: alterações concorrentes e novas chaves de
idempotência ainda podem gerar pedidos com o mesmo carrinho ativo. A prioridade
é alta para essa etapa do produto; o desenho e os contratos entre Cart e Order
estão aprovados na microtarefa 42. A implementação segue as microtarefas 43–48,
sem ser introduzida como correção lateral nas tarefas acima ou acrescentar Inventory,
Payment, Saga ou Outbox automaticamente.

## Validação PostgreSQL

Reexecutar migrations, persistência e concorrência com PostgreSQL local
disponível. Na revisão geral, 270 testes passaram e 19 testes de integração
falharam por conexão recusada em localhost:5432. Essa limitação não confirma
nem descarta problemas de banco e deve acompanhar as entregas pertinentes.
Na microtarefa 25, Order foi revalidado com PostgreSQL disponível e todos os
131 testes passaram. Na microtarefa 26, Cart foi revalidado com PostgreSQL e
todos os 67 testes passaram. Na microtarefa 28, Catalog foi revalidado com
PostgreSQL e todos os 85 testes passaram, encerrando a pendência de validação
de banco da revisão. Cart também passou nos 76 testes da microtarefa 27.

Diferenças de packages shopsphere/shosphere e outras alterações cosméticas
ficam fora destas microtarefas; não há necessidade de renomeação para resolver
os problemas funcionais listados.

## Próxima fase — Inventory

Análise inicial concluída e plano registrado em
[inventory-service/PLAN.md](inventory-service/PLAN.md). O módulo contém apenas
a estrutura inicial; o teste de contexto foi reexecutado e aprovado (1 teste).
Não foram criados endpoints, entidades, migrations ou integrações nesta análise.

A microtarefa **29 — Definir regras e contratos mínimos** está concluída.
A proposta completa foi aprovada pelo utilizador e está em
[contracts/inventory-v1-proposal.md](contracts/inventory-v1-proposal.md).
Inclui saldo, ajustes, consultas e reservas locais, acesso por identidade de
serviço e expiração configurável. Não há implementação ou migration nova.
O plano detalha as microtarefas 30–41, desde arranque/Flyway, posição de estoque,
segurança, cadastro/ajustes e consultas até reservas, transições, expiração,
Gateway e observabilidade. A microtarefa **30 — Configurar arranque local e
Flyway** foi concluída: porta 8084 no perfil dev, datasource/JWT configuráveis,
Flyway PostgreSQL e JPA validate. Quatro testes focados sem infraestrutura e
cinco testes na suíte completa com PostgreSQL passaram, sem falhas ou ignorados
na execução com banco. Não há V1, entidades ou APIs de estoque nesta entrega.
A microtarefa **31 — Persistir a posição de estoque** foi concluída com entidade,
repositório e V1 de inventory_stock, invariantes no banco e versão otimista.
Nove testes focados e 18 testes com PostgreSQL local passaram; sem falhas ou
ignorados na execução com banco. A V1 foi aplicada apenas em schemas temporários.
A microtarefa **32 — Definir segurança e erros da API** foi concluída:
JWT/roles/azp, restrição por caminho/verbo e Problem Details no Inventory.
O cliente confidencial shopsphere-order e a role INVENTORY_SERVICE foram
provisionados no Keycloak local com audiência shopsphere-api e sem exportar
secret. A suíte com PostgreSQL e token real aprovou 73 testes, sem falhas ou
ignorados. Não há endpoints de negócio ou integração com Order nesta entrega.
A microtarefa **33 — Cadastrar estoque e validar produto** foi concluída:
POST ADMIN com ator JWT, validação Catalog fora da transação, timeouts,
V2 de movimentos INITIAL e idempotência por ator/tipo/chave. A suíte completa
com PostgreSQL e o teste de token real aprovou 120 testes, sem falhas ou
ignorados. V1 foi preservada; V2 foi aplicada somente em schemas de teste.
Detalhes em [inventory-service/REGISTRATION.md](inventory-service/REGISTRATION.md).
A microtarefa **34 — Repor e ajustar saldo** foi concluída: POST/GET ADMIN de
ajustes, movimentos ADJUSTMENT e replay do snapshot original, sem chamada ao
Catalog ou nova migration. Bloqueio transacional, limites, reservas e rollback
foram validados com PostgreSQL. Suíte completa: 134 testes aprovados, nenhuma
falha e um teste opcional de token real ignorado nesta execução.
Detalhes e arquivos em [inventory-service/ADJUSTMENTS.md](inventory-service/ADJUSTMENTS.md).
A microtarefa **35 — Consultar saldo e disponibilidade, incluindo batch** foi
concluída: GETs ADMIN com DTO existente, batch de 1–100 UUIDs distintos,
omissão de itens ausentes no lote e 404 individual. Doze testes focados de
HTTP/PostgreSQL passaram. Consultas não alteram estoque nem chamam Catalog.
Suíte completa de Inventory: 146 aprovados, nenhuma falha e um teste opcional
de token real do Keycloak ignorado nesta execução.
Detalhes em [inventory-service/QUERIES.md](inventory-service/QUERIES.md).
A microtarefa **36 — Persistir reservas e suas transições** foi concluída:
modelo de reserva/itens, estados ACTIVE/CONFIRMED/RELEASED/EXPIRED, vínculo ao
cliente de serviço e versão otimista. V3 cria as duas tabelas, sem modificar
V1/V2. Trinta testes focados passaram com PostgreSQL, incluindo upgrade V2,
reinício, constraints e rollback. Ainda não há endpoints ou efeitos de reserva
no saldo; as transições de negócio pertencem às etapas 38–39.
Suíte completa de Inventory: 166 aprovados, nenhuma falha e um teste opcional
de token real do Keycloak ignorado nesta execução. V3 aplicada somente em
schemas temporários dos testes.
Detalhes em [inventory-service/RESERVATION_PERSISTENCE.md](inventory-service/RESERVATION_PERSISTENCE.md).
A microtarefa **37 — Criar reserva idempotente** foi concluída: PUT/GET internos
com role INVENTORY_SERVICE e azp shopsphere-order, reserva de todos os itens
numa transação e replay equivalente sem novo débito ou renovação do prazo.
TTL padrão PT15M configurável. Concorrência, rollback e 100 itens validados
com PostgreSQL. Suíte completa: 194 aprovados, nenhuma falha e um teste
opcional de token real do Keycloak ignorado nesta execução. Sem migration nova,
Gateway ou integração com Order/Cart. Expiração automática fica para a etapa 39.
Detalhes em [inventory-service/RESERVATIONS.md](inventory-service/RESERVATIONS.md).
A microtarefa **38 — Liberar e confirmar reserva** foi concluída: POSTs internos
de confirmação/liberação, transições conforme estado e prazo, consumo/devolução
atômicos e repetições sem novos efeitos. Confirmação vencida retorna 409 sem
consumir; liberação vencida marca EXPIRED e devolve o reservado uma vez.
Validados com PostgreSQL: concorrência, preservação de outras reservas,
rollback integral, prazo após espera por lock e 100 itens. Sem nova migration
ou mudanças em outros serviços.
Suíte completa de Inventory: 231 aprovados, nenhuma falha e um teste opcional
de token real do Keycloak ignorado nesta execução.
Detalhes em [inventory-service/RESERVATION_TRANSITIONS.md](inventory-service/RESERVATION_TRANSITIONS.md).
A microtarefa **39 — Tratar expiração e reconciliação** foi concluída: job
configurável, padrão PT60S/lote 50, expiração atômica por reserva e recuperação
das vencidas persistidas após reinício. Cursor percorre as páginas mesmo com
falhas; SKIP LOCKED permite retomar reservas ocupadas noutra varredura.
Concorrência com liberação/confirmação e rollback validados com PostgreSQL.
V4 adiciona índice de busca, preservando V1–V3 e dados existentes.
Suíte completa: 248 aprovados, nenhuma falha e um teste opcional de token real
do Keycloak ignorado nesta execução. V4 aplicada somente em schemas de teste.
Detalhes em [inventory-service/EXPIRATION.md](inventory-service/EXPIRATION.md).
A microtarefa **40 — Expor somente as operações necessárias pelo Gateway** foi
concluída: as cinco operações administrativas de Inventory são encaminhadas
com ADMIN, URL configurável e timeouts dedicados. Caminhos internos de reserva
e verbos não aprovados permanecem sem encaminhamento. Headers, body, query,
status e Location preservados. Suíte completa do Gateway: 78 aprovados,
nenhuma falha ou teste ignorado. Sem migration, mudança de dependências ou
alteração no domínio de Inventory.
Detalhes em [api-gateway/INVENTORY.md](api-gateway/INVENTORY.md).
A microtarefa **41 — Acrescentar observabilidade mínima** foi concluída:
correlação HTTP local com X-Request-ID/MDC, logs seguros por status e contadores
por varredura de expiração. Sem novas dependências ou endpoints de health/métricas.
Detalhes em [inventory-service/OBSERVABILITY.md](inventory-service/OBSERVABILITY.md)
e validação em [inventory-service/TESTING.md](inventory-service/TESTING.md).
Na execução inicial, PostgreSQL estava indisponível. Revalidação em 2026-10-26:
68 testes PostgreSQL e um teste com token real Keycloak aprovados, sem falhas
ou ignorados, encerrando essa pendência de infraestrutura.
As reservas fazem parte da primeira entrega aprovada;
a ligação ao checkout continua para uma etapa posterior com decisão própria.
Implementar uma entrega de cada vez, após resolver suas decisões de contrato,
persistência e segurança.
