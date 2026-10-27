# Protocolo interno de checkout — microtarefa 44

Implementa os contratos aprovados na
[microtarefa 42](../contracts/cart-checkout-proposal.md), sobre a
[persistência local da etapa 43](CHECKOUT_PERSISTENCE.md).

## Rotas e respostas

| Verbo e caminho | Body | Resultado |
|---|---|---|
| PUT /internal/api/v1/cart-checkouts/{checkoutId} | `{"customerId":"<uuid>"}` | 201 inicial, 200 replay; Location aponta para GET da mesma intenção. |
| GET /internal/api/v1/cart-checkouts/{checkoutId} | Sem body | 200 com estado e snapshot. |
| POST /internal/api/v1/cart-checkouts/{checkoutId}/complete | `{"orderId":"<uuid>"}` | 200 inicial/replay equivalente. |
| POST /internal/api/v1/cart-checkouts/{checkoutId}/abort | `{"customerId":"<uuid>"}` | 200 inicial/replay, incluindo aborto antecipado. |

UUIDs e campos obrigatórios são validados antes da chamada ao serviço local.
A resposta inclui checkoutId, customerId, cartId, status, items
(productId/quantity), orderId, preparedAt, completedAt e abortedAt.
Campos nullable são incluídos explicitamente. O snapshot é ordenado por
productId e preservado nas repetições, conforme a etapa 43.

Ausência de preparação/carrinho retorna 404. Carrinho vazio/ocupado,
customerId incompatível, tentativa de preparar ABORTED, completar com outro
orderId ou abortar COMPLETED retornam 409. Identidade de serviço diferente
da dona do registro resulta em 404 no serviço local; o filtro HTTP exige
primeiro a identidade autorizada. Erros MVC seguem
[api-errors.md](../contracts/api-errors.md), sem expor valores rejeitados.

## Segurança

A cadeia dedicada de prioridade 1 cobre /internal/**. Somente as quatro
combinações acima permitem acesso, exigindo simultaneamente:

- JWT com assinatura, validade e issuer verificados pelo decoder já configurado.
- Audiência shopsphere-api, ou a audiência explicitamente configurada em
  `security.checkout.audience` via `SHOPSPHERE_CART_CHECKOUT_AUDIENCE`.
- Realm role CART_CHECKOUT_SERVICE em realm_access.roles.
- Claim azp exatamente shopsphere-order.

Scope OAuth2 não substitui realm role. ADMIN e INVENTORY_SERVICE isoladamente
não permitem checkout interno. O cliente persistido vem exclusivamente de azp;
o sub do service account não é tratado como customerId. Headers adicionais
não escolhem a identidade. customerId interno é confiado ao Order autenticado,
que deverá derivá-lo do principal da API pública na integração futura.

Ausência/token inválido retorna 401 e permissão insuficiente retorna 403, com
application/problem+json, mensagens genéricas, errors vazio e instance sem query
string. WWW-Authenticate dos handlers Bearer é preservado. Não são criadas sessões.
O conversor público de authorities e os handlers de /api/v1/carts/** permanecem
inalterados. A validação adicional de audiência aplica-se somente à cadeia
interna, preservando as exigências globais já configuradas pelo ambiente.

As rotas internas não são encaminhadas pelo Gateway e são negadas mesmo com
ADMIN e a role de checkout. Os testes verificam tanto o predicado real de
roteamento quanto a cadeia real de segurança do Gateway. Não houve mudança
de configuração de rotas ou permissões do Gateway.

## Keycloak local

Foi criada a realm role CART_CHECKOUT_SERVICE e adicionada ao service account
de shopsphere-order e aos seus role scope mappings. As roles anteriores foram
preservadas; o token real manteve INVENTORY_SERVICE e não incluiu ADMIN.
O cliente existente continua confidencial, com service accounts, Full Scope
Allowed desativado, flows de utilizador desativados e mapper shopsphere-api.
Não houve alteração de outros clientes, do Inventory ou dos mappers existentes.

O script [Enable-CheckoutServiceRole.ps1](keycloak/Enable-CheckoutServiceRole.ps1)
faz apenas essas adições e verifica as pré-condições. Não cria um cliente
alternativo se shopsphere-order estiver ausente. Usa os endpoints da
[API administrativa do Keycloak](https://www.keycloak.org/docs-api/latest/rest-api/index.html)
para roles, service-account-user, role-mappings e scope-mappings.

Para reproduzir sobre o cliente existente em desenvolvimento:

```powershell
$adminPassword = Read-Host 'Password administrativa do Keycloak local' -AsSecureString
.\keycloak\Enable-CheckoutServiceRole.ps1 -AdminPassword $adminPassword
```

O script recebeu uma segunda execução bem-sucedida após correção da consulta
de existência da role; não remove atribuições. Não contém password/secret nem
exporta tokens. O secret de shopsphere-order foi consultado somente em memória
para obter um token de teste por client_credentials; variáveis de processo foram
removidas ao finalizar. Credenciais não foram adicionadas ao Git.

## Arquivos e limites

- `config/CartCheckoutSecurityConfig.java` e import em `config/SecurityConfig.java`.
- `controller/CartCheckoutController.java`.
- `mapper/request/CheckoutCustomerRequest.java` e `CheckoutCompleteRequest.java`.
- `mapper/response/CartCheckoutResponse.java`.
- `src/main/resources/application.yaml`: audiência interna configurável.
- `keycloak/Enable-CheckoutServiceRole.ps1`.

Os caminhos Java ficam sob src/main/java/br/com/hadryan/shosphere/cart.
Testes e resultados em [TESTING.md](TESTING.md). Não há migration nova,
alteração dos serviços/transições da etapa 43, cliente HTTP no Order ou
reconciliador. Order ainda usa o checkout anterior. A ativação conjunta das
etapas 45–48 permanece necessária antes do uso operacional do protocolo.
