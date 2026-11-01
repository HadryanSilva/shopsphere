# Payment — base local (microtarefa 57)

Base do simulador persistente de desenvolvimento aprovado no
[contrato Payment–Order–Inventory](../contracts/order-payment-inventory-proposal.md).
Esta etapa configura arranque, schema, segurança e erros. Os casos de uso,
entidades/repositórios JPA e endpoints financeiros pertencem à microtarefa 58.
Não há captura, reembolso, provedor externo ou dinheiro real nesta etapa.

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
normal. Ainda não há entidades JPA; a validação de colunas mapeadas será
necessária na etapa 58. As constraints atuais são verificadas diretamente com SQL.

## Persistência inicial

[V1__create_payments.sql](src/main/resources/db/migration/V1__create_payments.sql)
cria somente tabelas do próprio Payment:

- `payments`: identidade por `order_id`, cliente de serviço, valor EUR positivo
  em `NUMERIC(19,2)`, estado, barreira de cancelamento, datas e versão.
- `payment_movements`: identidade `(order_id, kind)` para uma linha CAPTURE e
  uma REFUND por pedido; FK local exige valor/moeda iguais aos da intenção.

Os estados previstos são PROCESSING, CAPTURED, DECLINED, CANCELLED,
REFUND_PENDING e REFUNDED. Checks restringem combinações de estado, barreira
e datas. O schema não executa transições nem garante sozinho a atomicidade
entre estado e movimentos. Imutabilidade da intenção, validação decimal sem
arredondamento, efeitos simulados, concorrência e rollback serão implementados
e testados com o domínio na etapa 58.

## Segurança e erros

Resource Server valida assinatura, issuer, expiração e audiência. A política
reservada para o contrato interno exige simultaneamente realm role
`PAYMENT_SERVICE` e `azp=shopsphere-order`:

| Método | Caminho autorizado pela política |
|---|---|
| PUT / GET | /internal/api/v1/payments/{orderId} |
| POST | /internal/api/v1/payments/{orderId}/cancel |

Essas operações ainda não têm controllers de produção. Os testes usam
controllers exclusivos de teste para verificar a política. Outras rotas/verbos
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
Esse provisionamento foi efetuado localmente nesta entrega; não há export de
realm nem reprovisionamento automático no repositório.

Order, Inventory, Gateway e frontend permanecem sem alterações. O Order ainda
não chama Payment. Execução e resultados de validação em [TESTING.md](TESTING.md).
