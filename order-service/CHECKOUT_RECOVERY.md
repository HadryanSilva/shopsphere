# Recuperação automática de checkout — microtarefa 47

CheckoutRecoveryJob retoma PREPARING, PREPARED, COMPLETION_PENDING e
ABORT_PENDING pela agenda nextAttemptAt persistida na V4. Usa
SnapshotCheckoutUseCase.resume(checkoutId), o cliente interno e a identidade
client_credentials existentes; não depende de JWT do utilizador.

## Configuração

O job só é registrado com checkout.snapshot.enabled=true e
checkout.snapshot.recovery.enabled=true. O segundo parâmetro vem habilitado em
application.yaml, mas o modo snapshot continua false por padrão nesta etapa.
Credenciais e URLs continuam descritas em [CHECKOUT_ORCHESTRATION.md](CHECKOUT_ORCHESTRATION.md).

| Propriedade sob checkout.snapshot.recovery | Padrão | Efeito |
|---|---|---|
| enabled | true | Permite parar o job sem mudar o modo de checkout. |
| interval | PT60S | Espera inicial e intervalo após cada varredura. |
| batch-size | 50 | Máximo de candidatos por varredura. |
| lease | PT60S | Adia uma intenção reclamada; um processo interrompido pode ser retomado após esse prazo. |
| initial-backoff | PT60S | Espera da primeira falha, inclusive nas tentativas pelo POST. |
| max-backoff | PT15M | Teto do backoff exponencial por intenção. |
| warn-after | PT15M | Idade a partir da qual uma tentativa ainda pendente gera aviso. |

Durações usam ISO-8601; interval, lease, warn-after e initial-backoff devem ser
positivos, max-backoff deve ser maior ou igual a initial-backoff e batch-size
deve ser positivo. Pode-se substituir as propriedades pela configuração Spring
do ambiente. Não há abandono por número de tentativas, purge ou liberação por TTL.

## Concorrência e falhas

A seleção ordena por próxima tentativa e checkoutId, limita o lote e usa
SKIP LOCKED. Cada candidato é revalidado/reclamado numa transação REQUIRES_NEW
curta, que adia sua agenda antes de liberar o lock. Outro worker ignora agendas
futuras. Nenhuma transação/lock atravessa Cart, Catalog ou token endpoint.

O prazo de claim coordena tentativas, sem prometer exclusividade durante toda
a execução: uma tentativa lenta, um replay público ou uma transição de estado
pode sobrepor execuções. As transições existentes revalidam estado sob lock;
checkoutId estável, unicidade de origem e operações internas idempotentes
impedem duplicar os efeitos. Ajustar lease aos timeouts quando necessário.

Falhas registram categoria enum segura, contador e próxima tentativa. A espera
dobra (60, 120, 240, 480, 900 segundos por padrão), com teto e sem limite de
tentativas. O cálculo usa o contador relido sob lock. Resultados de estados
obsoletos não alteram a agenda de outro estado ou de intenções terminais.

O worker registra falhas locais que escapam da orquestração; se esta já gravou
uma tentativa, o fallback não incrementa novamente. Se o próprio banco estiver
indisponível, o claim persistido permite nova tentativa após seu prazo. Falhas
de uma intenção não interrompem as outras do lote. Após reinício, a seleção
volta a encontrar todas as agendas vencidas, sem estado necessário em memória.

COMPLETION_PENDING retoma complete sem recriar ou recalcular o pedido.
ABORT_PENDING retoma abort mantendo a decisão terminal de não criar pedido.
PREPARING repete PUT com o mesmo checkoutId, inclusive após resposta perdida;
PREPARED revalida Catalog e pode criar o pedido posteriormente a um 502/503,
conforme contrato aprovado. Estados COMPLETED/ABORTED saem da agenda.

## Operação e limites

Logs incluem somente checkoutId, estado, contador e categoria segura. Não
incluem endereço, cliente, chave pública, tokens, secrets, body downstream,
mensagem de exceção ou stack trace. Uma tentativa pendente há 15 minutos gera
aviso e continua recuperável; o aviso ocorre nas tentativas, não num monitor
independente durante indisponibilidade do banco.

Para intervir, investigar por checkoutId, restaurar banco/Cart/Catalog/Keycloak
ou credenciais e manter/retomar o job. Não apagar intenções nem liberar Cart
manualmente por SQL. Não há endpoint novo de intervenção. Recuperação continua
a usar a intenção original e o protocolo aprovado.

Não houve migration nova, dependência, mudança de API pública ou segurança,
alteração no Cart/Gateway/Inventory, integração Payment ou mensageria. A
validação entre processos reais e a operação integrada foram acrescentadas na
etapa 48, conforme [CHECKOUT_OPERATION.md](CHECKOUT_OPERATION.md).
Testes e resultados em [TESTING.md](TESTING.md).
