# Segurança do Inventory — microtarefa 32

As regras abaixo estão implementadas no próprio Inventory. Não dependem da
validação do Gateway. O cadastro de estoque foi implementado na microtarefa
33, ajustes na 34 e consultas de saldo na 35. Criação/consulta de reservas foram
implementadas na 37; confirmação/liberação na 38 e job de expiração na 39.
O job é interno ao processo e não cria endpoint ou permissões novas.
Os testes da matriz usam
controllers auxiliares marcados como componentes de teste, além dos testes
específicos dos controllers de cadastro, ajustes, consultas e reservas.

## Matriz de acesso

| Operação aprovada | Permissão |
|---|---|
| POST `/api/v1/inventory/items` | Realm role ADMIN |
| GET `/api/v1/inventory/items/{productId}` | Realm role ADMIN |
| GET `/api/v1/inventory/items/batch` | Realm role ADMIN |
| POST `/api/v1/inventory/items/{productId}/adjustments` | Realm role ADMIN |
| GET `/api/v1/inventory/items/{productId}/adjustments/{id}` | Realm role ADMIN |
| PUT/GET `/internal/api/v1/inventory/reservations/{orderId}` | Realm role INVENTORY_SERVICE e azp shopsphere-order |
| POST `/internal/api/v1/inventory/reservations/{orderId}/confirm` ou `/release` | Realm role INVENTORY_SERVICE e azp shopsphere-order |
| Qualquer outro caminho/verbo | Negado |

Não há consultas anónimas. ADMIN isoladamente não concede acesso interno;
INVENTORY_SERVICE isoladamente não concede administração. Scopes OAuth2
continuam SCOPE_..., e não são tratados como realm roles ROLE_.... Claims
realm_access de formato incorreto não concedem roles. A identidade de serviço
vem de azp no JWT validado, nunca de header adicional ou body da requisição.

O Resource Server padrão do Spring Boot valida assinatura e tempo/issuer do
JWT. A audiência padrão shopsphere-api está na configuração principal, valendo
também fora de dev, e pode ser substituída por SHOPSPHERE_JWT_AUDIENCE. Em outros
ambientes, configurar o issuer e manter a audiência aprovada; não transportar
a configuração permissiva do perfil test.
As permissões e o vínculo da reserva ao cliente de serviço são verificações
distintas: a segunda será implementada nos casos de uso de reservas.

A aplicação usa sessões STATELESS e Bearer tokens, sem formulário de login ou
autenticação HTTP Basic. CSRF fica desativado para essa API. As rotas internas
continuam fora do Gateway; no deploy, restringir também a rede conforme o
contrato aprovado. Na microtarefa 40, o Gateway passou a expor somente os
caminhos administrativos com ADMIN, sem rotear as reservas internas. Detalhes
em [../api-gateway/INVENTORY.md](../api-gateway/INVENTORY.md). As validações de
JWT e permissões do próprio Inventory permanecem intactas.

## Problem Details

O tratamento MVC segue o padrão de Catalog/Cart/Order, com type, title, status,
detail, instance e errors. Entrada inválida retorna 400; recurso ausente, 404;
conflitos de integridade/locking, 409; falhas inesperadas, 500 genérico.
Erros 502/503 representados explicitamente por ResponseStatusException mantêm
o status e ocultam o motivo interno. Não é adicionado cliente HTTP nesta etapa.

No Inventory, 401/403 dos filtros também usam application/problem+json,
com mensagens genéricas e errors vazio. O header WWW-Authenticate dos handlers
Bearer padrão é preservado. instance contém só o caminho, sem query string.
Não devolver tokens, SQL, stack traces ou valores rejeitados. AuthenticationException
e AccessDeniedException não são convertidas em 500 pelo advice MVC.
Essa extensão dos erros de filtros é local ao Inventory; os outros serviços
mantêm seus handlers existentes.

## Keycloak local: configuração e reprodução

No realm shopsphere, foi criado nesta entrega o cliente shopsphere-order e a
realm role INVENTORY_SERVICE, sem alterar clientes ou roles de outros módulos.
O cliente é confidencial, habilita service accounts e desativa standard,
implicit e direct access grants. Full Scope Allowed fica desativado.

O arquivo [shopsphere-order-client.json](keycloak/shopsphere-order-client.json)
contém a representação sem secret para criar/importar o cliente. Ele não é um
export completo do realm nem inclui a atribuição de roles ao service account.
Não reaplicar sobre um cliente existente sem conferir suas configurações.

Para reproduzir num realm de desenvolvimento novo:

1. Criar a realm role INVENTORY_SERVICE.
2. Criar/importar o cliente do JSON; manter o client scope padrão roles existente.
3. Atribuir INVENTORY_SERVICE ao service account desse cliente.
4. Incluir INVENTORY_SERVICE nos role scope mappings do cliente, mantendo
   Full Scope Allowed desativado. Não atribuir ADMIN ao service account.
5. Manter o audience mapper shopsphere-api-audience do JSON no cliente, incluindo
   shopsphere-api no access token, sem alterar os mappers dos demais clientes.
6. Obter token por client_credentials com o secret gerido pelo Keycloak.
   Verificar azp, realm_access.roles e aud sem registrar o token ou o secret.

Os passos seguem a [API administrativa](https://www.keycloak.org/docs-api/latest/rest-api/index.html)
e a documentação de [service accounts](https://www.keycloak.org/docs/latest/server_admin/index.html#_service_accounts).
O secret não foi exportado para arquivos: foi consultado apenas em memória
para obter o token de teste. Recuperá-lo pelo canal administrativo apropriado
quando a integração Order for implementada; não o colocar no Git.

O cliente de navegador usado por administradores também precisa emitir aud
shopsphere-api e a role ADMIN. Não foram modificados seus mappers ou usuários
nesta entrega, nem executado um fluxo de login administrativo real.

## Validação automatizada

`InventoryJwtSecurityTests` usa RSA/JWKS locais e o decoder real do Spring Boot.
Verifica permissões por verbo/caminho, combinações role/azp, ADMIN versus
serviço, ausência de sessão/CSRF, assinatura inválida, token expirado, issuer
incorreto, audiência ausente/incorreta, token malformado e scopes que não
substituem roles. `ApiExceptionHandlerTests` cobre o contrato MVC de erros.

```powershell
.\gradlew.bat test --tests '*InventoryJwtSecurityTests' --tests '*ApiExceptionHandlerTests' --tests '*InventoryServiceApplicationTests' --offline --no-daemon
```

O teste `acceptsProvisionedLocalKeycloakServiceToken` é opt-in pela variável
SHOPSPHERE_TEST_INVENTORY_SERVICE_TOKEN. Ele usa o token real fornecido no
header e o JWKS do Keycloak para validar a autenticação/autorização numa rota
auxiliar de teste, sem criar uma reserva real. O issuer local padrão é
http://localhost:8180/realms/shopsphere; pode ser substituído por
SHOPSPHERE_TEST_KEYCLOAK_ISSUER. Sem o token, somente esse teste é ignorado.
Não imprimir a variável nem incluir seu valor em comandos versionados.

O teste não representa comunicação Order -> Inventory nem um endpoint de
reservas já disponível. A V2 de movimentos INITIAL e o cadastro foram entregues
na microtarefa 33; ajustes e reservas continuam nas suas microtarefas.

## Resultado desta entrega

A suíte completa, executada com `.\gradlew.bat test --offline --no-daemon
--rerun-tasks` e as duas integrações opt-in ativadas em memória, aprovou os
73 testes, sem falhas ou ignorados. O token real foi obtido do Keycloak local
por client_credentials e validado com seu JWKS nos filtros reais do Inventory.
O contexto de persistência usa um filtro explícito para excluir TestComponent:
o runner não deve descobrir os controllers auxiliares nem registrar rotas
duplicadas. Todas as verificações JPA/Flyway/constraints continuam ativas.
