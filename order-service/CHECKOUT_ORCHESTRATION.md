# Orquestração do checkout por snapshot — microtarefa 46

O novo SnapshotCheckoutUseCase utiliza a intenção persistida da etapa 45 e o
protocolo interno do Cart da etapa 44. Mantém POST /api/v1/orders, endereço,
Idempotency-Key, resposta 201/Location e GET por proprietário. A identidade
pública continua vindo do JWT do utilizador; itens e quantidades vêm do Cart,
e nomes/preços/moeda são revalidados no Catalog. O body público não decide
identidade, preços, totais ou os itens efetivamente comprados.

## Seleção durante o desenvolvimento

O sistema ainda está em desenvolvimento. Uma configuração temporária seleciona
exatamente um caso de uso por execução, permitindo testar a integração por etapas:

| Propriedade | Valor/configuração |
|---|---|
| checkout.snapshot.enabled | false por padrão; SHOPSPHERE_CHECKOUT_SNAPSHOT_ENABLED controla o modo novo. |
| checkout.snapshot.token-uri | SHOPSPHERE_ORDER_TOKEN_URI; obrigatório no modo novo. |
| checkout.snapshot.client-id | shopsphere-order, conforme a identidade aprovada no Cart. |
| checkout.snapshot.client-secret | SHOPSPHERE_ORDER_CLIENT_SECRET; obrigatório no modo novo, sem valor versionado. |
| checkout.snapshot.connect-timeout | 2000 ms para Cart e token endpoint. |
| checkout.snapshot.read-timeout | 5000 ms para Cart e token endpoint. |

No desenvolvimento local, o token endpoint existente é
http://localhost:8180/realms/shopsphere/protocol/openid-connect/token.
services.cart.url continua configurando o host do Cart. Fornecer credenciais
do cliente existente por variável de ambiente, sem as gravar no Git.

Não foi alterada a configuração do Keycloak nem ativado o modo novo no ambiente
normal nesta entrega. A flag não constitui um plano de rollout de produção:
as etapas 47–48 completarão recuperação automática e validação integrada local.
Não são necessárias janelas de manutenção, drenagem ou tratamento adicional
de registros legados, conforme orientação posterior do utilizador.

## Sequência e fronteiras transacionais

1. Validar a requisição e consultar pedido existente por cliente/chave/endereço.
   O resultado já persistido continua 201; uma conclusão pendente é tentada sem
   tornar a resposta dependente do sucesso remoto. O comportamento anterior de
   pedidos já existentes é preservado, sem criar origem ou intenção artificial.
2. Criar/retomar intenção durável com checkoutId estável e endereço original.
3. Em PREPARING, enviar PUT interno ao Cart e validar identidade, PREPARED,
   timestamps, cartId, itens positivos e distintos. Persistir o snapshot.
4. Em PREPARED, consultar Catalog uma vez em lote e validar completude, nomes,
   estado, preço e moeda coerente. Calcular snapshots com BigDecimal, usando
   as regras monetárias do Order existente.
5. Gravar pedido/itens/origem e COMPLETION_PENDING na transação local da etapa
   45. A unicidade de sourceCartId impede pedidos duplicados mesmo se o Cart
   responder incorretamente a duas intenções concorrentes.
6. Depois do commit, enviar complete e verificar checkoutId/customerId/cartId,
   itens, estado COMPLETED e orderId. Confirmar a conclusão localmente.

As chamadas ao Cart, ao Catalog e ao token endpoint ficam fora das transações
do banco. Nenhum lock é mantido durante HTTP. resume(checkoutId) retoma trabalho
durável sem JWT do utilizador e será utilizado pelo reconciliador da etapa 47;
não é um endpoint público.

## Identidade de serviço e transporte

CartCheckoutClient usa RestClient da stack existente, sem dependências novas.
CheckoutServiceTokenProvider obtém token por client_credentials e mantém cache
somente em memória, renovando antes da expiração. Não usa CurrentUserTokenProvider
nem propaga o Bearer do utilizador. Catalog continua usando a leitura pública
existente, sem token de serviço adicionado indiscriminadamente.

O token endpoint recebe formulário com grant_type, client_id e client_secret.
Não há logging de tokens, secrets ou payloads de autenticação. Respostas de
integração são convertidas em categorias seguras, sem transportar body de erro
ou credenciais para a API pública. 401/403 do Cart invalidam o cache para a
próxima tentativa e tornam-se 502; não se tornam 401/403 do utilizador.

Conexão/leitura indisponível retorna 503; outras falhas HTTP ou payload inválido
retornam 502. Não há retry na aplicação dentro do cliente HTTP nem redirecionamento
automático. Repetições explícitas de checkout usam a mesma intenção e os métodos
idempotentes definidos no contrato. Os quatro métodos internos estão mapeados;
a preparação perdida é retomada por PUT equivalente na próxima execução.

## Falhas, aborto e retomada

- 404/409 ao preparar e conflitos de produto/valor gravam ABORT_PENDING antes
  de chamar abort. O aborto é idempotente e inclui o caso anterior à preparação.
  Após confirmação, ABORTED mantém a chave consumida e repete o erro de negócio.
- Se abort falhar, preservar ABORT_PENDING e o erro original; replay tenta
  novamente sem consultar Catalog ou criar pedido. Nunca liberar por TTL.
- Payload remoto inválido ou timeout preserva PREPARING/PREPARED e registra
  categoria segura/tentativa/próxima data. Não abortar por suposição sobre uma
  chamada cujo resultado é ambíguo.
- Falha ao gravar o snapshot depois de preparar remotamente mantém a intenção
  recuperável; replay repete o mesmo checkoutId, sem criar outra intenção.
- Se o pedido já foi gravado, falha/resultado inválido de complete mantém
  COMPLETION_PENDING e retorna 201 com o pedido. Replay ou resume tenta completar
  sem recalcular valores. Falha adicional no registro da tentativa não apaga
  a pendência durável nem transforma o pedido confirmado no banco em aborto.
- Resultados concorrentes revalidam o estado local. A decisão de aborto não
  pode reverter um commit já concluído; nesse caso prevalece o pedido salvo.

A primeira agenda de falha usa 60 segundos. O job, backoff progressivo, lotes
e observabilidade da recuperação pertencem à etapa 47. Até essa etapa, retomada
ocorre por replay da mesma chave ou chamada Java a resume, sem worker automático.

## Arquivos e validação

- `client/CartCheckoutClient.java`, `CheckoutServiceTokenProvider.java` e `CheckoutIntegrationFailure.java`.
- `config/CartCheckoutClientConfig.java`.
- `mapper/response/CartCheckoutResponse.java`.
- `service/impl/SnapshotCheckoutUseCase.java` e seleção condicional em `CreateOrderUseCaseImpl.java`.
- Consulta transacional da intenção em `service/impl/CheckoutIntentTransaction.java`.
- `src/main/resources/application.yaml`.

Os caminhos Java ficam sob src/main/java/br/com/hadryan/shosphere/order.
Comandos/resultados em [TESTING.md](TESTING.md): 196 testes aprovados com
PostgreSQL. Os testes do cliente usam servidores HTTP locais; a orquestração usa
PostgreSQL real e Cart/Catalog simulados. O POST/GET público é exercitado com
controller, segurança e persistência reais, usando decoder simulado; a validação
criptográfica continua coberta pela suíte existente de JWT do Order.

Não houve nova migration, alteração de DTO público, segurança, Gateway, Inventory,
Payment, Outbox ou mensageria. Nenhum secret foi obtido ou alterado no Keycloak
nesta entrega. A validação entre processos Cart/Order/Catalog fica para a etapa 48.
