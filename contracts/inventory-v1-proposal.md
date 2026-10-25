# Inventory v1 — regras e contratos aprovados na microtarefa 29

**Estado: proposta completa aprovada pelo utilizador; microtarefa 29 concluída.**
Este documento é a referência para as próximas entregas, mas não representa
funcionalidades implementadas. Nenhuma rota, role, cliente Keycloak ou tabela
descrita abaixo deve ser considerada existente antes da respectiva implementação.
Não houve provisionamento de infraestrutura ou alteração de outros serviços.

## Escopo aprovado

- Primeira entrega: cadastro, ajustes, consultas e reservas locais no Inventory.
- Sem ligação automática ao Order/Cart, broker, Outbox, Saga ou Payment.
- Um estoque por UUID de produto, sem armazéns, lotes, frações ou backorder.
- Saldo físico e reservado entre 0 e 2147483647; disponível = físico - reservado.
- Sempre 0 <= reservado <= físico; disponível é calculado, não uma terceira coluna.
- Reservar aumenta reservado; confirmar reduz físico e reservado; liberar ou
  expirar reduz reservado. Nenhuma dessas operações altera preço ou produto.
- Cadastro exige produto existente no Catalog, inclusive INACTIVE/OUT_OF_STOCK:
  cadastrar ou repor estoque não muda seu estado comercial. Inventory não
  sincroniza ProductStatus; Order continuará revalidando o Catalog na integração futura.
- Não apagar posições de estoque nem disponibilizar edição livre dos contadores.

Exemplo: físico 10/reservado 0 -> reservar 3 resulta em 10/3/disponível 7.
Confirmar essa reserva resulta em 7/0/disponível 7. Liberá-la, em vez de
confirmar, resulta em 10/0/disponível 10. Nunca executar os dois efeitos.

## Identidade e acesso

- Validar assinatura, expiração e issuer do JWT no Inventory, independentemente do Gateway.
- Administração e consultas de saldo: role ADMIN já usada no projeto.
- Reservas e consultas internas: novo cliente confidencial proposto
  `shopsphere-order`, via client credentials, com role `INVENTORY_SERVICE` aprovada para criação.
  Exigir essa role e `azp=shopsphere-order`; ADMIN isoladamente não concede esse acesso.
- Não criar/alterar cliente, role ou mapper Keycloak nesta microtarefa.
  Provisionamento e testes da política precisam ocorrer na entrega de segurança.
- Nenhum endpoint público/anónimo de saldo detalhado nesta primeira versão.
- Atores de ajustes vêm do `sub`; identidade de serviço vem do JWT validado.
  Não aceitar customerId, ator ou identidade de serviço escolhidos no body.
- Exigir audiência `shopsphere-api` na API Inventory após configurar/verificar
  o mapper no Keycloak; não enfraquecer validações existentes de outros módulos.
- Gateway exporá apenas os caminhos administrativos aprovados, mantendo ADMIN.
  Caminhos `/internal/**` não serão roteados externamente. A separação de caminho
  não substitui autenticação/autorização no Inventory nem restrição de rede no deploy.

## API administrativa aprovada para implementação

Prefixo `/api/v1/inventory`. São contratos novos, ainda não implementados.

| Operação | Requisição | Sucesso |
|---|---|---|
| Cadastrar estoque | POST `/items`, body `{productId,onHand}`, header UUID `Idempotency-Key` | 201 com Location `/api/v1/inventory/items/{productId}` e posição |
| Consultar posição | GET `/items/{productId}` | 200 com `{productId,onHand,reserved,available,version,updatedAt}` |
| Consultar lote | GET `/items/batch?productIds=...` | 200 com lista de posições, sem garantia de ordem |
| Ajustar saldo físico | POST `/items/{productId}/adjustments`, body `{delta,reason}`, header UUID `Idempotency-Key` | 201 com `{id,productId,delta,reason,onHandAfter,reservedAfter,createdAt}` e Location da operação |
| Consultar ajuste | GET `/items/{productId}/adjustments/{id}` | 200 com a representação da operação |

Cadastro aceita onHand zero ou positivo; ajustes exigem delta inteiro não zero
e reason não vazio com até 500 caracteres. Calcular o resultado sem overflow;
rejeitar saldo final negativo, acima do limite ou menor que reservado. Uma
reposição é simplesmente um ajuste com delta positivo.

Batch exige 1 a 100 UUIDs distintos. IDs sem cadastro são omitidos; nenhuma
posição encontrada retorna `[]`. Saldo zero retorna uma posição normal.
GET individual sem cadastro retorna 404. O limite de batch controla tamanho
de URL e não implica limite de itens de um pedido futuro.

Idempotência de cadastro/ajuste é delimitada por `(sub, tipo de operação,
Idempotency-Key)`, comparando productId e todos os campos relevantes do body.
Repetição idêntica não altera saldo novamente; payload diferente retorna 409.
Mesmo cadastro com outra chave para produto já cadastrado retorna 409.
Não reservar a chave em caso de rollback. Normalização não será implícita:
reason é comparado exatamente como recebido.

Replay de cadastro retorna 201, o mesmo Location e a posição atual; não promete
reproduzir byte a byte o body original. Replay de ajuste retorna 201 e a mesma
operação com os contadores registrados na aplicação original. Ajustes e saldo
devem ser persistidos na mesma transação local.

## API interna de reservas aprovada para implementação

Prefixo `/internal/api/v1/inventory/reservations`. O UUID do pedido identifica
uma única reserva; não há necessidade de outra chave de idempotência para ela.

| Operação | Requisição | Sucesso |
|---|---|---|
| Reservar todos os itens | PUT `/{orderId}`, body `{items:[{productId,quantity}]}` | 201 na primeira criação; 200 na repetição equivalente; Location do recurso |
| Consultar/reconciliar | GET `/{orderId}` | 200 com a reserva persistida |
| Confirmar consumo | POST `/{orderId}/confirm`, sem body | 200 com a reserva |
| Liberar | POST `/{orderId}/release`, sem body | 200 com a reserva |

Resposta da reserva: `{orderId,status,items,expiresAt,createdAt,updatedAt}`.
Datas em UTC; estados ACTIVE, CONFIRMED, RELEASED e EXPIRED. Reservas guardam
também o cliente de serviço autenticado; consultas/mutações não ignoram esse vínculo.
Recurso fora do alcance da identidade retorna 404, sem expor os itens.

Exigir 1 a 100 produtos distintos por reserva, com quantity positiva até o
limite inteiro. Duplicatas são rejeitadas, não somadas. A ordem dos itens não
altera a intenção; comparar conjuntos ordenados por productId com quantidades.
Uma nova intenção após cancelamento/expiração exige novo orderId; o mesmo ID
nunca reativa uma reserva nem representa um segundo pedido.

Todas as posições devem existir e ter saldo disponível. Produto sem posição
retorna 404; saldo insuficiente retorna 409. Não reservar parcialmente. Replay
com os mesmos itens devolve o estado atual, inclusive um estado terminal,
sem renovar o prazo; mesmo orderId com itens diferentes retorna 409.

| Estado atual | Confirmar | Liberar |
|---|---|---|
| ACTIVE, antes do prazo | CONFIRMED; consumir uma vez | RELEASED; devolver uma vez |
| ACTIVE, prazo vencido | 409; não consumir | EXPIRED; devolver uma vez |
| CONFIRMED | 200; sem novo consumo | 409; sem devolução |
| RELEASED | 409 | 200; sem nova devolução |
| EXPIRED | 409 | 200; sem nova devolução |

Prazo inicial aprovado: 15 minutos, configurável pelo serviço e nunca escolhido
pelo cliente. Replay não reinicia o relógio. Job de expiração aprovado a
cada 60 segundos, também configurável, com processamento em lotes pequenos.
GET não altera estado. Até o job/liberação processar uma reserva vencida, ela
pode continuar ACTIVE no registro e manter saldo indisponível; a confirmação
já deve respeitar expiresAt. Isso favorece indisponibilidade temporária em vez
de venda excedente. Após reinício, o job deve recuperar vencidas persistidas.

## Persistência e concorrência aprovadas para implementação

Nomes aprovados para as próximas migrations, ainda inexistentes:

| Tabela | Conteúdo e integridade principal |
|---|---|
| `inventory_stock` | product_id UUID PK, on_hand/reserved INTEGER com checks, version BIGINT e timestamps |
| `inventory_movements` | UUID da operação, tipo INITIAL/ADJUSTMENT, produto, delta, reason, ator sub, chave UUID, contadores após operação e created_at; unicidade da chave por ator/tipo |
| `inventory_reservations` | order_id UUID PK, service_client_id, status, expires_at, version e timestamps |
| `inventory_reservation_items` | reserva, produto e quantity; unicidade de produto por reserva, quantity > 0 e FKs apenas dentro de Inventory |

O movimento INITIAL também guarda o onHand inicial (inclusive zero) para
comparação do cadastro original. Não usar retenção/limpeza automática dos
registros de idempotência nesta versão. Definir tamanhos de strings e nomes
finais de constraints na documentação técnica da entrega correspondente.

Bloquear posições durante mutações; para múltiplos produtos, ordenar por UUID.
Nas transições, bloquear primeiro a reserva e depois as posições nessa ordem.
Usar a mesma ordem na expiração. Ajustes bloqueiam apenas a posição. A criação
de reserva deve estabelecer primeiro a identidade única do pedido e depois
bloquear posições, tratando corridas de criação sem duplicar efeitos.

Atualizar reserva/itens/contadores numa única transação local. Tratar violações
de idempotência somente após rollback e em leitura independente, como no Order;
não capturar qualquer falha de integridade como se fosse replay. Resultado de
conflito não pode reverter uma liberação que o contrato diga ter concluído.
Chamadas ao Catalog acontecem fora da transação, sem acesso direto ao seu banco.

Testes obrigatórios incluem última unidade concorrente, reservas sobrepostas,
ordem de bloqueios, rollback de vários itens, repetição de ajustes e reservas,
confirmação/liberação/expiração concorrentes e limites numéricos. Não presumir
que @Version da reserva proteja automaticamente mutações dos filhos.

## Configuração, erros e limites desta decisão

Porta local aprovada: 8084; datasource aponta para inventory_db já previsto em
init.sql, com valores externalizáveis. Não criar ou resetar banco nesta tarefa.
Flyway gerencia migrations; JPA usa validate. Manter Java/Boot/Gradle atuais.
Cliente Catalog deve usar a stack HTTP existente onde suficiente, com timeouts
de conexão/leitura e sem retry automático de operações mutáveis; a escolha de
adapter será fechada na microtarefa 33 sem alterar clientes de outros módulos.

Erros seguem `application/problem+json`: 400 para entrada inválida, 401 para
ausência/token inválido, 403 para permissão insuficiente, 404 para recurso
ausente/inacessível, 409 para conflito de saldo/estado/idempotência/concorrência,
502 para resposta HTTP inválida/falha upstream e 503 para indisponibilidade de
conexão/leitura. Não devolver SQL, tokens ou dados internos. Falha de consulta
Catalog não prova inexistência do produto; distinguir 404 de timeout/5xx.

Health, métricas e permissões operacionais serão definidos na microtarefa 41.
Não criar agora uma stack de observabilidade. Integração Order/Cart, recuperação
de falhas distribuídas e escolha HTTP/eventos continuam fora desta proposta.
O limite aprovado de 100 itens de reserva deve ser reconciliado com o checkout
antes da futura integração, sem alterar silenciosamente o contrato atual do Order.
