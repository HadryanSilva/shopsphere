# Recuperação financeira agendada — microtarefa 61

PaymentRecoveryJob retoma o [coordenador financeiro](PAYMENT_FLOW.md) somente
para trabalhos persistidos em payment_work. Não seleciona pedidos apenas por
PENDING_PAYMENT e não depende de replay público ou JWT do utilizador.
Ativo quando checkout.snapshot.enabled e payment.recovery.enabled são true.
Desabilitar o worker conserva os trabalhos para retomada posterior.

## Claim e autorização das chamadas

V9 acrescenta claim_token/claimed_until, attempt_count e last_warning_at, mais
índice de agenda para fases não terminais. Preserva V1–V8 e as provas anteriores.
due retorna um lote ordenado por nextAttemptAt/orderId, ignorando DONE/CLOSED,
datas futuras e leases vigentes, com SKIP LOCKED. A transação termina antes do
claim individual. claim bloqueia StockWork → Order → PaymentWork, também com
SKIP LOCKED, revalida elegibilidade e grava token/prazo em commit próprio.

O coordenador recebe esse token explicitamente e confere dono/prazo antes de
novas chamadas e ao persistir os resultados. Uma execução que perdeu o claim
não grava provas, muda fase, registra falha ou inicia novo efeito após detectar
a perda. A entrada interna sem claim não toma emprestado o claim de outro.
finish só libera, agenda e avisa quando o token ainda é o atual; um worker
antigo não limpa o claim nem o bookkeeping do sucessor.

Não há HTTP sob transação. Uma chamada já autorizada pode continuar em voo
depois de expirar o lease; ele não cancela transporte. A retomada consulta ou
repete operações na mesma identidade. As barreiras duráveis do Inventory e do
Payment, as fases irreversíveis e as provas continuam impedindo confirmação
tardia após compensação segura. Não há promessa de entrega exatamente uma vez.

## Agenda e retomada

- Progresso ou resposta pendente válida: nova passagem após interval.
- Falha técnica/configuração/persistência: initial-backoff dobra pela contagem
  acumulada até max-backoff. Falha já registrada não é contada novamente pelo
  fallback do finish. Reinício não zera a contagem.
- Antes de resolver o estoque, uma data futura de expiração menor antecipa a
  agenda. Isso não declara expiração pelo relógio do Order: a prova remota
  continua obrigatória. Prazo já passado não cria loop imediato.
- REVIEW_REQUIRED: somente GETs diagnósticos de Payment/Inventory, sem alteração
  das provas, do estado público ou efeitos financeiros; intervalo max-backoff.
- DONE/CLOSED: fora da seleção e sem nova agenda. Uma contradição tardia que
  marque revisão segue as regras da etapa 60.

Falhas são isoladas por pedido. Se o processo cai ou o bookkeeping fica
indisponível, o claim persistido torna-se elegível após o lease. Não há abandono
por idade ou quantidade de tentativas: capturas e reembolsos pendentes continuam
recuperáveis. Inconsistências permanecem em revisão, com consultas/avisos
limitados e sem mutações automáticas incompatíveis.

Após warn-after de idade pendente, um aviso persiste last_warning_at; o mesmo
intervalo limita novos avisos mesmo após reinício ou troca de processo. Logs
contêm apenas orderId, fase, contador e categoria segura. Não imprimem JWT,
secret, claim token, endereço, body ou texto downstream, nem stack traces das
falhas capturadas pelo job.

## Configuração

Todas as durações e batch-size devem ser positivos; max-backoff não pode ser
menor que initial-backoff.

| Propriedade payment.recovery.* | Variável | Padrão |
|---|---|---|
| enabled | SHOPSPHERE_PAYMENT_RECOVERY_ENABLED | true, condicionado também ao snapshot |
| interval | SHOPSPHERE_PAYMENT_RECOVERY_INTERVAL | PT60S |
| batch-size | SHOPSPHERE_PAYMENT_RECOVERY_BATCH_SIZE | 50 |
| lease | SHOPSPHERE_PAYMENT_RECOVERY_LEASE | PT60S |
| initial-backoff | SHOPSPHERE_PAYMENT_INITIAL_BACKOFF | PT60S |
| max-backoff | SHOPSPHERE_PAYMENT_MAX_BACKOFF | PT15M |
| warn-after | SHOPSPHERE_PAYMENT_WARN_AFTER | PT15M |

O scheduler local fornece três threads para Cart, estoque e pagamento. Uma
execução HTTP bloqueada não ocupa a capacidade das outras duas tarefas. Um
scheduler customizado é preservado e deve oferecer capacidade equivalente.
fixedDelay, duração do lote e carga determinam o tempo efetivo de reconciliação;
a agenda não promete execução em tempo real antes do vencimento da reserva.

API pública, timeouts HTTP e identidade de serviço permanecem os existentes.
Payment continua sendo o simulador de desenvolvimento, sem dinheiro real/PSP.
Testes e resultados em [TESTING.md](TESTING.md); integração financeira com seis
JARs permanece para a etapa 62.
