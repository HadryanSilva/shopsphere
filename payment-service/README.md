# Payment — simulador persistente de desenvolvimento

Simulador persistente de desenvolvimento aprovado no
[contrato Payment–Order–Inventory](../contracts/order-payment-inventory-proposal.md).
As microtarefas 57–58 entregam arranque, schema, segurança, erros e os endpoints
internos de captura/consulta/cancelamento com reembolso integral simulado.
CAPTURED e REFUNDED representam movimentos locais no banco do simulador.
Não há provedor externo, dados de cartão ou dinheiro real.

## Arranque

Com PostgreSQL e Keycloak locais disponíveis, executar em `payment-service`:

```powershell
.\gradlew.bat bootRun --offline --no-daemon --args='--spring.profiles.active=dev'
```

| Variável de ambiente | Padrão do perfil dev |
|---|---|
| SHOPSPHERE_PAYMENT_PORT | 8085 |
| SHOPSPHERE_PAYMENT_DB_URL | jdbc:postgresql://localhost:5432/payment_db |
| SHOPSPHERE_PAYMENT_DB_USER | postgres |
| SHOPSPHERE_PAYMENT_DB_PASSWORD | postgres, exclusivamente desenvolvimento local |
| SHOPSPHERE_JWT_ISSUER | http://localhost:8180/realms/shopsphere |
| SHOPSPHERE_JWT_AUDIENCE | shopsphere-api |

`init.sql` já declara `payment_db`; o entrypoint PostgreSQL só executa esse
arquivo ao inicializar um volume novo. Não remover volumes para iniciar Payment.
Fora de dev, fornecer datasource e issuer pelas propriedades externas do Spring.
O perfil `test` desativa datasource/Flyway apenas para o teste de contexto.

Flyway utiliza o datasource da aplicação e `classpath:db/migration`.
`ddl-auto=validate` e `open-in-view=false` ficam habilitados na configuração
normal. As entidades Payment e PaymentMovement são mapeadas ao schema existente
e validadas no arranque. V1 permanece intacta; a etapa 58 não adiciona migration.

## Persistência inicial

[V1__create_payments.sql](src/main/resources/db/migration/V1__create_payments.sql)
cria somente tabelas do próprio Payment:

- `payments`: identidade por `order_id`, cliente de serviço, valor EUR positivo
  em `NUMERIC(19,2)`, estado, barreira de cancelamento, datas e versão.
- `payment_movements`: identidade `(order_id, kind)` para uma linha CAPTURE e
  uma REFUND por pedido; FK local exige valor/moeda iguais aos da intenção.

Os estados previstos são PROCESSING, CAPTURED, DECLINED, CANCELLED,
REFUND_PENDING e REFUNDED. Checks restringem combinações de estado, barreira
e datas. A aplicação preserva a intenção, valida valor exatamente representável
sem arredondamento e grava movimento/resultado na mesma transação local.

## Protocolo interno

PUT e POST cancel recebem exclusivamente `amount` e `currency`:

```json
{"amount":25.00,"currency":"EUR"}
```

| Operação | Resultado |
|---|---|
| PUT /internal/api/v1/payments/{orderId} | 201 para intenção nova com resultado; 200 para replay; 202 se PROCESSING. Location aponta para o GET. |
| GET /internal/api/v1/payments/{orderId} | 200 com o estado persistido ou 404 ausente/inacessível. Somente leitura. |
| POST /internal/api/v1/payments/{orderId}/cancel | 200 CANCELLED/DECLINED/REFUNDED ou 202 REFUND_PENDING. Pode preceder o primeiro PUT. |

Resposta: orderId, status, amount, currency, createdAt, updatedAt, capturedAt e
refundedAt. Datas UTC, instantes ainda não aplicáveis explicitamente null.
Replays sem transição preservam datas e representação. O pedido identifica a
intenção, vinculada ao azp do serviço; rotação de token/sub da mesma service
account não cria identidade financeira nova.

Body ausente, malformado, campos extras, moeda diferente de EUR, valor não
positivo, precisão excedida ou fração não representável retornam 400. Escalas
equivalentes como 25.0/25.000 são aceitas. Termos monetários diferentes retornam
409; intenção de outro cliente retorna 404 sem revelar seus termos.
PUT depois de CANCELLED/REFUND_PENDING/REFUNDED retorna 409. DECLINED é recusa
definitiva bem-sucedida no protocolo; replay não tenta capturar novamente.

A criação usa `INSERT ... ON CONFLICT DO NOTHING`, cobrindo tanto a PK quanto
o índice único dos termos, e depois bloqueia a raiz com PESSIMISTIC_WRITE.
A intenção PROCESSING faz commit antes de tentar capturar. Cancelar grava
CANCELLED sem captura, ou REFUND_PENDING quando já capturado, em commit próprio
antes de tentar reembolsar. Cada resultado financeiro e seu movimento único
fazem commit juntos, sob o mesmo bloqueio. Falha posterior mantém a intenção ou
a decisão anterior; replay retoma a mesma identidade.

Se cancelamento vence, captura atrasada é recusada. Se captura vence, cancel
reembolsa integralmente uma vez. Não há chamadas HTTP dentro dessas transações:
o adaptador toma apenas decisões locais e o efeito simulado é o movimento no
banco. Um futuro PSP exige outro contrato; não pode substituir esse adaptador
presumindo atomicidade entre banco e provedor externo.

## Controles do simulador

`payment.simulator.enabled` é false por padrão e true no perfil dev. Desabilitado,
tentativas de captura/reembolso retornam 503 preservando o estado pendente.
Não há campo no HTTP para selecionar resultado. No perfil dev:

| Variável | Padrão | Valores |
|---|---|---|
| SHOPSPHERE_PAYMENT_SIMULATOR_CAPTURE_RESULT | CAPTURED | CAPTURED, DECLINED, PENDING, UNAVAILABLE |
| SHOPSPHERE_PAYMENT_SIMULATOR_REFUND_RESULT | REFUNDED | REFUNDED, PENDING, UNAVAILABLE |

PENDING conserva PROCESSING/REFUND_PENDING e responde 202; UNAVAILABLE responde
503 sem concluir o movimento. Alterar configuração e reiniciar permite testar
a retomada por repetição do PUT/cancel. GET não executa efeitos; ainda não há
worker no Payment. A recuperação coordenada pelo Order pertence às etapas
60–61. Resposta perdida após commit é resolvida por consulta/replay, sem nova
captura ou novo reembolso. Esses controles são globais ao processo de dev.

## Segurança e erros

Resource Server valida assinatura, issuer, expiração e audiência. A política
reservada para o contrato interno exige simultaneamente realm role
`PAYMENT_SERVICE` e `azp=shopsphere-order`:

| Método | Caminho autorizado pela política |
|---|---|
| PUT / GET | /internal/api/v1/payments/{orderId} |
| POST | /internal/api/v1/payments/{orderId}/cancel |

Os controllers de produção implementam somente essas três operações.
Outras rotas/verbos
são negados, inclusive para ADMIN; scopes OAuth não substituem a realm role.
Sem sessão, Basic ou formulário de login. Erros usam Problem Details com
`errors`, inclusive 401/403, preservando o desafio Bearer. Falhas internas e de
persistência não expõem SQL, tokens ou detalhes do corpo; logs de exceções
inesperadas registram somente o tipo.

No Keycloak local, acrescentar a realm role `PAYMENT_SERVICE` à service account
de `shopsphere-order` **e** aos realm role scope mappings desse cliente.
Manter `fullScopeAllowed=false`, as roles CART_CHECKOUT_SERVICE/INVENTORY_SERVICE
e o mapper de audiência `shopsphere-api` existentes. Não conceder a role ao
cliente web. Credenciais devem permanecer fora do repositório e dos logs.
Esse provisionamento foi efetuado localmente na etapa 57; não há export de
realm nem reprovisionamento automático no repositório.

Order, Inventory, Gateway e frontend permanecem sem alterações. O Order ainda
não chama Payment. Execução e resultados de validação em [TESTING.md](TESTING.md).
