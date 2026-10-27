# Observabilidade mínima — microtarefa 41

## HTTP e correlação

`RequestCorrelationFilter` executa antes da segurança e gera um UUID local por
requisição. Devolve `X-Request-ID` e coloca o mesmo valor no MDC `requestId`
durante o processamento síncrono. Headers de correlação recebidos são ignorados;
não constituem identidade, autorização ou chave de idempotência. O contexto
anterior é restaurado inclusive após exceção, sem apagar outras entradas MDC.

O evento `Inventory HTTP request` contém requestId, status, completed e durationMs.
Status 409 e 5xx, ou saída por exceção, usam WARN; os restantes usam INFO.
Também cobre rejeições de autenticação/autorização, preservando seus contratos.
Se completed=false, status é o valor observado antes de o container tratar a
exceção, podendo ainda ser 200; não representa necessariamente o status final.
Falhas inesperadas do advice incluem requestId e somente o tipo da exceção.

Não são registrados Authorization, cookies, JWT, body, query, caminho, identidade
do utilizador ou mensagens/stack traces de exceções nos eventos acrescentados.
O ID é local ao Inventory: não há tracing distribuído, propagação ao Catalog,
MDC assíncrono ou garantia de preservação do header por proxies externos.

## Expiração e reservas pendentes

Cada varredura tem sweepId. O resumo INFO `Inventory expiration sweep` contém:

- candidates: reservas vencidas ACTIVE selecionadas nas páginas desta varredura;
- expired: workers que retornaram true após a transação de expiração;
- skipped: workers que retornaram false, incluindo disputa por lock ou estado já alterado;
- failed: workers que lançaram exceção;
- completed: se a paginação terminou sem falha de consulta.

Falhas individuais usam WARN com sweepId, orderId e failureType. Falhas de consulta
usam WARN com sweepId e failureType, preservando a exceção para o agendador.
O resumo também é emitido se uma consulta falhar. completed=true não significa
ausência de falhas individuais nem ausência de reservas ainda vencidas.

Esses contadores descrevem trabalho observado, não medem todas as reservas ACTIVE
ou o backlog global. Falhas repetidas ou skipped persistente exigem investigação;
a varredura seguinte continua sendo o mecanismo existente de reconciliação.
Não foram adicionadas consultas de contagem nem alterados locks, prazos ou transações.

## Health e métricas

Nesta entrega foram escolhidos logs locais, sem Actuator, Micrometer, endpoints,
novas dependências ou infraestrutura. Health, métricas persistentes, alertas e sua
exposição continuam pendentes de decisão específica de acesso e operação.

## Arquivos de implementação e testes

- `src/main/java/br/com/hadryan/shosphere/inventory/config/RequestCorrelationFilter.java`
- `src/main/java/br/com/hadryan/shosphere/inventory/exception/ApiExceptionHandler.java`
- `src/main/java/br/com/hadryan/shosphere/inventory/service/ReservationExpirationJob.java`
- `src/test/java/br/com/hadryan/shosphere/inventory/config/RequestCorrelationFilterTests.java`
- `src/test/java/br/com/hadryan/shosphere/inventory/config/InventoryJwtSecurityTests.java`
- `src/test/java/br/com/hadryan/shosphere/inventory/service/ReservationExpirationJobTests.java`

Resultados e limitações da execução em [TESTING.md](TESTING.md).
