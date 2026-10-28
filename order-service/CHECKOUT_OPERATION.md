# Checkout integrado — microtarefa 48

O fluxo por snapshot conecta Gateway, Cart, Order e Catalog via HTTP real.
Order usa identidade de serviço no protocolo interno do Cart, revalida os
dados comerciais no Catalog e persiste o pedido antes de concluir o carrinho.
O reconciliador retoma trabalho durável após falhas/reinício.

## Executar a validação integrada local

Pré-requisitos: Java 25, PostgreSQL local acessível e permissão para criar e
remover schemas de teste. Não é necessário iniciar manualmente microsserviços
nem alterar Keycloak. Execute na raiz do repositório:

```powershell
foreach ($module in @('catalog-service', 'cart-service', 'order-service', 'api-gateway')) {
    Push-Location $module
    try {
        .\gradlew.bat bootJar --offline --no-daemon
        if ($LASTEXITCODE -ne 0) { throw "Falha no bootJar de $module" }
    } finally { Pop-Location }
}

$env:SHOPSPHERE_TEST_POSTGRES_URL = 'jdbc:postgresql://localhost:5432/order_db'
$env:SHOPSPHERE_TEST_CHECKOUT_INTEGRATION = 'true'
Push-Location order-service
try {
    .\gradlew.bat test --offline --no-daemon --tests '*CheckoutFlowIntegrationTests'
} finally {
    Pop-Location
    Remove-Item Env:SHOPSPHERE_TEST_CHECKOUT_INTEGRATION -ErrorAction SilentlyContinue
}
```

SHOPSPHERE_TEST_POSTGRES_USER/PASSWORD configuram o acesso ao PostgreSQL;
na ausência, usam os valores locais do Compose, seguindo os demais testes.
O modo offline pressupõe dependências já disponíveis no cache Gradle.

A suíte exige as duas variáveis de ativação e JARs bootJar atualizados nos
quatro módulos. Sem ativação, é ignorada. Não cria dependência Gradle entre
serviços, não baixa bibliotecas novas e não usa mocks de controller, aplicação,
repositório ou decoder JWT dos serviços.

CheckoutTestRuntime inicia os quatro JARs em processos próprios e portas de
loopback livres. Cria três schemas exclusivos checkout_flow_* no banco de
teste informado, um por serviço de domínio; as aplicações acessam somente o
próprio schema. As migrations reais e Hibernate validate são executados em
cada processo. O teste instala Order V3 com um pedido histórico e deixa o JAR
aplicar V4, sem modificar migrations ou dados normais de desenvolvimento.

Um emissor/token endpoint local assina JWTs RSA e serve JWKS. Os filtros e
decoders reais validam assinatura, issuer, audiência, role e azp; Order obtém
token por client_credentials, sem propagar o JWT público ao Cart interno.
Proxies de teste encaminham HTTP aos serviços reais e injetam falhas antes
da entrega ou após a resposta de um commit. O worker real é agendado a cada
dois segundos nesta suíte, com backoff/lease reduzidos, sem modificar defaults.

Ao terminar, a suíte encerra somente seus processos e remove somente schemas
criados por ela. Logs ficam em order-service/build/checkout-integration/<UUID>/,
fora do Git, para diagnóstico; tokens e secrets não são registrados pelo harness.
Interrupção forçada da JVM de testes pode impedir a limpeza: identificar os
processos e schemas dessa execução antes de qualquer limpeza manual.

## Cobertura e correção encontrada

- POST/GET pelo Gateway, 201/Location, snapshots financeiros imutáveis,
  isolamento de proprietário inclusive ADMIN e conflito de endereço.
- Quatro POSTs simultâneos na mesma chave convergem no mesmo pedido; outra
  chave durante preparação recebe 409. Mutações concorrentes são bloqueadas,
  leitura permanece disponível e um novo ACTIVE pode ser criado após conclusão.
- Perda de resposta de preparação, conclusão, aborto e criação pública;
  reinícios reais do Cart/Order, repetição pela mesma chave e retomada pelo
  worker sem novo pedido ou token do utilizador.
- Catalog indisponível preserva PREPARED; restauração retoma o snapshot.
  Falta de permissão de serviço retorna 502 e continua recuperável; corrigir
  a identidade permite ao worker prosseguir.
- Aborto antecipado impede PUT atrasado; chave abortada permanece consumida,
  correção do produto permite nova chave; complete equivalente é idempotente
  e outro orderId retorna 409.
- 401/403 nas APIs reais, audiência/role/azp internos e bloqueio do protocolo
  pelo Gateway; consulta de pedido histórico antes/depois de reinício e V4.

A primeira execução revelou persistência de entidades desatualizadas entre
transações na mesma requisição do Order com Open EntityManager in View ativo.
Uma tentativa podia receber conflito otimista após o worker avançar a intenção,
inclusive ao registrar aborto. application.yaml agora define
spring.jpa.open-in-view=false no Order: cada fronteira transacional relê seu
estado, e os DTOs continuam construídos dentro das transações existentes.
Os testes concorrentes mantêm as expectativas de 201/replay e aborto durável.

## Executar o fluxo com Keycloak local

Catalog e Cart usam seus perfis dev, Gateway é a entrada externa na porta 8080
e Order usa dev na porta 8083. As URLs padrão dos serviços são as existentes
nos respectivos application-dev.yaml. Antes de iniciar Order no modo novo:

- Confirmar o cliente confidencial shopsphere-order, service account, audiência
  shopsphere-api e role CART_CHECKOUT_SERVICE, conforme [CHECKOUT_API.md do Cart](../cart-service/CHECKOUT_API.md).
- Fornecer SHOPSPHERE_CHECKOUT_SNAPSHOT_ENABLED=true,
  SHOPSPHERE_ORDER_TOKEN_URI=http://localhost:8180/realms/shopsphere/protocol/openid-connect/token
  e SHOPSPHERE_ORDER_CLIENT_SECRET por ambiente seguro, sem gravar no Git.
- Iniciar o serviço com o perfil dev. checkout.snapshot.recovery.enabled=true
  mantém a recuperação automática; intervalos e backoff normais permanecem
  60 segundos, lote 50 e teto de 15 minutos, conforme [CHECKOUT_RECOVERY.md](CHECKOUT_RECOVERY.md).

O default snapshot=false foi preservado. Selecionar explicitamente o modo novo
no arranque; esta entrega não ativa aplicações num ambiente em execução.
O frontend continua usando JWT do utilizador, endereço e Idempotency-Key no
POST /api/v1/orders. Repetir a mesma chave/endereço consulta ou retoma a compra.

## Intervenção e limites

Depois de 502/503, o pedido ainda pode ser criado pelo worker. Conservar a
mesma chave; não criar outra para contornar preparação pendente. Depois de
201, o pedido está persistido, mesmo se o Cart ainda estiver bloqueado.
Após aborto definitivo, corrigir o problema e usar nova chave, depois da
liberação. Nunca liberar preparação apenas por idade.

Investigar pelos logs seguros de checkoutId/estado/categoria. Restabelecer
PostgreSQL, Cart, Catalog ou identidade/credenciais no Keycloak e manter/retomar
o worker; os registros persistidos permitem retomada sem edição de SQL.
Não apagar intenções, desbloquear Cart manualmente, recalcular pedido salvo
ou marcar conclusão sem a confirmação idempotente do Cart. Não há endpoint
de intervenção, purge ou abandono por contagem de tentativas.

Esta suíte usa identidade de teste, sem provisionar ou consultar Keycloak real.
O teste opt-in acceptsProvisionedLocalKeycloakServiceToken do Cart continua
disponível mediante SHOPSPHERE_TEST_CART_CHECKOUT_TOKEN; a execução real desse
teste deve ser reportada separadamente. A suíte integrada não comprova a
configuração atual de um realm externo nem o login PKCE do frontend.

Não há garantia de estoque/pagamento: o pedido permanece PENDING_STOCK.
Inventory, Payment, RabbitMQ, Outbox e Saga não participam deste fluxo.
Não houve contrato público, migration, dependência ou infraestrutura nova.
