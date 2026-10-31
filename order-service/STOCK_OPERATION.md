# Operação e validação integrada Order–Inventory — microtarefa 55

Conclui a sequência 49–55 do [contrato aprovado](../contracts/order-inventory-proposal.md).
O checkout por snapshot conclui o Cart depois do commit do pedido; o worker de
estoque reserva, acompanha e compensa pelo Inventory real. Persistência,
cliente e agenda estão descritos em [STOCK_PERSISTENCE.md](STOCK_PERSISTENCE.md),
[STOCK_FLOW.md](STOCK_FLOW.md) e [STOCK_RECOVERY.md](STOCK_RECOVERY.md).

## Executar os testes de processos reais

Pré-requisitos: Java 25, PostgreSQL local e dependências Gradle em cache. O
harness cria/remove somente schemas exclusivos de teste; não requer iniciar
microsserviços manualmente. Na raiz do repositório:

```powershell
foreach ($module in @('catalog-service', 'cart-service', 'inventory-service', 'order-service', 'api-gateway')) {
    Push-Location $module
    try {
        .\gradlew.bat bootJar --offline --no-daemon
        if ($LASTEXITCODE -ne 0) { throw "Falha no bootJar de $module" }
    } finally { Pop-Location }
}

$env:SHOPSPHERE_TEST_POSTGRES_URL = 'jdbc:postgresql://localhost:5432/order_db'
$env:SHOPSPHERE_TEST_STOCK_INTEGRATION = 'true'
Push-Location order-service
try {
    .\gradlew.bat test --offline --no-daemon --tests '*StockFlowIntegrationTests'
} finally {
    Pop-Location
    Remove-Item Env:SHOPSPHERE_TEST_STOCK_INTEGRATION -ErrorAction SilentlyContinue
}
```

SHOPSPHERE_TEST_POSTGRES_USER/PASSWORD permitem usar outra credencial local.
O banco deve permitir schemas temporários. Nunca apontar a suíte a um banco
remoto/compartilhado sem autorização específica. A suíte é opt-in e exige JARs
atualizados; não acrescenta dependências de build entre serviços.

Para a regressão completa do Order, habilitar também
SHOPSPHERE_TEST_CHECKOUT_INTEGRATION=true e executar
`.\gradlew.bat test --offline --no-daemon --rerun-tasks` no módulo. A suíte
anterior CheckoutFlowIntegrationTests continua validando o protocolo Cart–Order
com recuperação de estoque desabilitada nesse modo do harness; ela não tem
Inventory nem promete reserva. A nova StockFlowIntegrationTests habilita os
cinco serviços e o worker de estoque. Ambas podem ser executadas na mesma suíte.

CheckoutTestRuntime inicia JARs reais em processos/portas de loopback próprios,
com quatro schemas checkout_flow_* independentes para Catalog, Cart, Order e
Inventory. Cada aplicação usa somente seu schema. Order parte de V3 com um
pedido histórico e aplica as migrations atuais, sem fabricar trabalho para ele;
Inventory aplica suas migrations reais. Hibernate permanece em validate.

Emissor/JWKS/token endpoint locais assinam JWTs RSA. Os filtros/decoders reais
verificam assinatura, issuer, audiência, roles e azp; Order usa client_credentials
com CART_CHECKOUT_SERVICE e INVENTORY_SERVICE. Proxies encaminham para as
aplicações e injetam indisponibilidade, atraso, resposta perdida ou incompatível.
Nenhum controller, aplicação, repositório ou decoder dos serviços é simulado.

Os intervalos menores, lease de 1s, TTL curto em cenário específico e uma
segunda instância real do Order pertencem apenas ao harness. Defaults normais
continuam intactos. Logs ficam em order-service/build/checkout-integration/<UUID>/.
Ao terminar, o harness encerra somente seus processos e remove somente schemas
registrados por ele. Uma interrupção forçada pode impedir limpeza; identificar
os recursos dessa execução antes de qualquer ação manual.

## Cobertura integrada

- POST inicial 201/PENDING_STOCK, reserva posterior/PENDING_PAYMENT, GET/replay
  com estado atual e valores imutáveis, preço atual do Catalog ignorado no replay,
  proprietário isolado e Cart concluído sem reabertura automática.
- POSTs concorrentes na mesma chave produzem um pedido/uma intenção de estoque;
  dois pedidos disputando o último saldo têm um vencedor e rejeição compensada.
  Reposição não ressuscita o pedido cancelado; nova compra usa novo Cart/chave.
- Order já commitado aguarda COMPLETED do Cart sem chamar Inventory/gastar TTL;
  o worker progride após restauração da conclusão, sem replay público.
- Resposta de PUT perdida após reserva real, reinícios de Order/Inventory e GET
  de recuperação preservam orderId, itens, prazo e saldo reservado único.
- Resposta de abort perdida depois da barreira real, inclusive sem reserva
  existente, é recuperada após reinício. PUT posterior recebe 409.
- Inventory indisponível mantém o pedido consultável/replay 201 e não cancela
  por suposição; restauração retoma sem revalidar o preço financeiro.
- Ausência de INVENTORY_SERVICE no token de serviço é falha técnica; restaurar
  a emissão correta permite retomada automática com token renovado.
- JSON incompatível leva a REVIEW_REQUIRED, enquanto outra intenção avança;
  CONFIRMED inesperado exige revisão, não confirma pedido/pagamento e não desfaz
  consumo físico. O confirm é injetado explicitamente pelo teste, nunca pelo Order.
- ACTIVE com prazo vencido e job de expiração desabilitado, após reinícios,
  termina somente quando o aborto real confirma EXPIRED e devolve reservado.
- Duas instâncias reais de Order e um PUT retido antes da entrega por mais que
  o lease: outra execução obtém a barreira de aborto; liberar o PUT atrasado
  resulta em 409 no Inventory, sem saldo reservado tardio ou reabertura.
- Cem produtos passam numa única reserva; 101 recebem 409 com aborto durável
  do Cart antes de criar pedido/reserva. A edição do carrinho permanece possível.
- Constraint temporária em stock_work provoca rollback local após sucesso
  remoto; removê-la permite recuperar por GET sem novo PUT. A constraint afeta
  apenas o orderId do cenário num schema exclusivo e é removida no finally.
- Filtros reais do Inventory rejeitam anonimato/role/azp/audiência inadequados;
  Gateway não expõe GET/PUT/abort internos nem a ADMIN/identidade de serviço.
- Tradução consistente das datas pelo proxy simula relógio do Inventory atrás
  do Order. Prazo aparentemente vencido pede aborto, mas a decisão real é
  RELEASED, não EXPIRED por suposição; saldo físico é preservado.

A suíte verifica também ausência de secret, JWT Bearer e endereço de entrega
nos logs dos processos. Resultados e comandos executados em [TESTING.md](TESTING.md).

## Usar o fluxo com Keycloak local

Iniciar Catalog, Cart, Inventory e Order com seus perfis dev e Gateway como
entrada externa. As portas locais são 8081/8082/8084/8083 e 8080 no Gateway.
Antes de habilitar snapshot no Order:

- Confirmar o cliente confidencial shopsphere-order, service account e as duas
  realm roles CART_CHECKOUT_SERVICE e INVENTORY_SERVICE no token, com audiência
  shopsphere-api e azp shopsphere-order. Não encaminhar JWT público para Inventory.
- Fornecer SHOPSPHERE_CHECKOUT_SNAPSHOT_ENABLED=true, SHOPSPHERE_ORDER_TOKEN_URI
  apontando ao token endpoint existente e SHOPSPHERE_ORDER_CLIENT_SECRET por
  ambiente seguro, sem gravar secret no Git ou linha de comando.
- Conferir services.cart.url, services.catalog.url e SHOPSPHERE_INVENTORY_URL,
  além de issuer/audiência no próprio Inventory. Defaults locais e timeouts
  estão em STOCK_FLOW.md; agenda/backoff/lease em STOCK_RECOVERY.md.
- Manter os jobs de conclusão do Cart e estoque habilitados. Desabilitar um job
  preserva o trabalho persistido; não o transforma em sucesso ou cancelamento.

O default snapshot=false foi preservado. Esta entrega não ativa aplicações de
uso normal nem muda o realm. Foi possível consultar a configuração existente
do Keycloak local e executar os testes opt-in de Inventory/Cart com tokens
reais obtidos por client_credentials, mantidos somente em memória/ambiente
dos processos de teste. Não houve provisioning ou alteração de roles/clientes.

Para repetir a verificação de token real, fornecer um token válido nas variáveis
SHOPSPHERE_TEST_INVENTORY_SERVICE_TOKEN e SHOPSPHERE_TEST_CART_CHECKOUT_TOKEN e
executar acceptsProvisionedLocalKeycloakServiceToken nos respectivos módulos.
Renovar o token antes de cada suíte; não imprimir ou salvar seu conteúdo.
Esses testes usam JWKS/issuer reais do realm existente. A suíte de cinco JARs
usa o emissor de teste controlado para injeção de falhas, não login PKCE nem
uma configuração completa de frontend/Keycloak.

## Interpretar o pedido e recuperar incidentes

201 significa pedido persistido, inicialmente PENDING_STOCK. Inventory pode
estar indisponível e o Cart ainda aguardando conclusão durável. Conservar a
mesma chave/endereço para replay; não criar outra chave para contornar pendência.
PENDING_PAYMENT significa que a última reconciliação observou reserva ACTIVE;
não comprova pagamento nem disponibilidade em tempo real. A reserva pode
expirar e o pedido ser cancelado depois. paymentStatus permanece null.

| Situação | Ação operacional |
|---|---|
| WAITING_CART/COMPLETION_PENDING | Restabelecer Cart/credenciais e o job de conclusão; estoque aguarda a prova COMPLETED. |
| UPSTREAM_UNAVAILABLE/PERSISTENCE_FAILURE | Restabelecer serviço/banco e manter o worker. O trabalho/identidade persistidos permitem retomar sem SQL manual. |
| AUTHENTICATION_CONFIGURATION | Conferir token endpoint, secret, issuer/audiência, roles e azp. 401/403 invalidam cache; falha não cancela o pedido. |
| ABORT_PENDING | Manter Inventory/worker disponíveis até prova de aborto. GET/404, idade, contador ou lease não encerram a compensação. |
| CLOSED/CANCELLED | Não repetir reserva nem reabrir Cart histórico. Corrigir a condição e fazer nova compra com novo Cart/chave/pedido. |
| REVIEW_REQUIRED | Investigar identidade/itens/datas/CONFIRMED inesperado pelos IDs e categorias seguras; não declarar sucesso ou desfazer consumo manualmente. |

Não apagar intenções/barreiras, zerar contadores, marcar status por edição de
SQL, recalcular snapshots ou liberar estoque pelo relógio local. Não há endpoint
manual de intervenção, purge automático ou abandono por prazo/tentativas.
Lease não cancela HTTP em voo; a segurança depende de idempotência, revalidação
local e barreira durável do Inventory, sem promessa de execução exatamente uma vez.

Não foram acrescentados Payment, confirm automático, eventos, RabbitMQ, Outbox,
Saga, novos contratos públicos ou migrations nesta etapa. A cadência efetiva
do worker depende da latência/carga dos lotes, conforme STOCK_RECOVERY.md.
