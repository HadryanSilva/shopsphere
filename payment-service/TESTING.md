# Validação do Payment — microtarefas 57–58

Java 25, Spring Boot 4.1.1 e wrapper Gradle existentes, sem alteração de versões.

## Execução

No diretório `payment-service`, sem infraestrutura externa:

```powershell
.\gradlew.bat test --offline --no-daemon
.\gradlew.bat bootJar --offline --no-daemon
```

Para incluir PostgreSQL local:

```powershell
$env:SHOPSPHERE_TEST_POSTGRES_URL = 'jdbc:postgresql://localhost:5432/payment_db'
.\gradlew.bat test --offline --no-daemon --rerun-tasks
```

Usuário/senha de teste usam os padrões locais de desenvolvimento; substituíveis
por `SHOPSPHERE_TEST_POSTGRES_USER` e `SHOPSPHERE_TEST_POSTGRES_PASSWORD`.
Os testes criam schemas `payment_startup_test_<UUID>` e
`payment_protocol_test_<UUID>` e removem exclusivamente
esses schemas no encerramento, sem alterar tabelas de outros schemas.

Para o teste opcional com Keycloak, fornecer `SHOPSPHERE_TEST_PAYMENT_SERVICE_TOKEN`
contendo um token client_credentials de `shopsphere-order`, emitido após o
provisionamento descrito no [README](README.md). Obtê-lo em memória usando
credenciais locais, sem gravar/imprimir token ou secret. O issuer padrão desse
teste é `http://localhost:8180/realms/shopsphere`, substituível por
`SHOPSPHERE_TEST_KEYCLOAK_ISSUER`. Além do teste isolado da política, a etapa 58
usa assinatura/JWKS reais nos controllers de produção com PostgreSQL para
capturar, consultar e reembolsar no simulador. Não há efeito monetário real.

## Cobertura

- Decoder JWT real com JWKS RSA local: role/azp, audiência/issuer, assinatura,
  expiração/nbf, ausência de token, Basic, roles inadequadas, scopes e caminhos
  ou verbos não permitidos; contrato Problem Details 401/403.
- Configuração Flyway/datasource e substituição das propriedades do perfil dev.
- Arranque com PostgreSQL/Flyway/EntityManagerFactory, reinício sem reaplicar V1,
  preservação de linhas e constraints de valor/moeda, estado, versão e datas.
- Unicidade de intenção e movimentos, vínculo local dos termos monetários,
  precisão/limite de NUMERIC e rejeição de movimentos incompatíveis.
- Problem Details de validação/404/409/5xx e logs sem detalhes sensíveis.

Na etapa 58, Hibernate `validate` verifica os mapeamentos JPA de Payment e
PaymentMovement contra V1. A suíte de protocolo acrescenta:

- HTTP 201/200/202, Location, nulls explícitos, validação/campos extras, precisão,
  limites exatos, recusa de negócio, 400/404/409/503 e isolamento de cliente.
- Repetições após perda lógica da resposta e recriação completa do contexto,
  retomando intenção/decisão/resultados persistidos no mesmo schema.
- Capturas e reembolsos concorrentes, cancelamentos iniciais concorrentes,
  intenções incompatíveis, cancelamento entre intenção e captura, captura com
  lock em voo e corrida do primeiro PUT com cancelamento.
- Falha injetada por trigger temporário depois da inserção do movimento e antes
  de concluir o estado: rollback remove o movimento e preserva PROCESSING ou
  REFUND_PENDING previamente commitados. O trigger pertence só ao schema gerado.
- Simulador desabilitado, indisponível ou pendente; GET sem efeitos e recusa
  terminal mesmo depois de reiniciar com configuração de captura aprovada.

Os reinícios são recriações de contextos Spring e EntityManagerFactory com
PostgreSQL persistente. Reinícios de processos/JARs e falhas da integração com
Order/Inventory ficam para a etapa 62. Nenhum teste efetua cobrança real ou
acessa PSP.

## Resultado da microtarefa 57 em 2026-11-01

`.\gradlew.bat test --offline --no-daemon --rerun-tasks` com PostgreSQL local
e token real Keycloak: **56 testes aprovados, zero falhas e zero ignorados**
(27 de segurança, 11 de erros, 2 de configuração Flyway, 15 de persistência
e 1 de contexto). Inclui rejeição de NaN na intenção e nos movimentos.
`.\gradlew.bat bootJar --offline --no-daemon` e `git diff --check` passaram.
V1 aplicada exclusivamente em schemas temporários dos testes.

O primeiro teste com token real retornou 403 porque a nova role ainda faltava
no escopo permitido do cliente Order. Após acrescentá-la sem ampliar
`fullScopeAllowed`, a suíte completa passou. Não foram reexecutadas as suítes
dos outros serviços, cujos arquivos permaneceram intactos; a integração dos
seis processos é a etapa 62.

## Resultado da microtarefa 58 em 2026-11-01

Após os testes focados, `.\gradlew.bat test --offline --no-daemon --rerun-tasks`
com PostgreSQL local e token real Keycloak aprovou **80 testes, zero falhas e
zero ignorados**: 27 de segurança, 11 de erros, 2 de configuração Flyway,
15 do schema/arranque, 24 do protocolo e 1 de contexto. O teste de token real
do protocolo executou captura, GET e reembolso pelos controllers de produção.
`.\gradlew.bat bootJar --offline --no-daemon` e `git diff --check` passaram.

A validação inicial de concorrência encontrou colisão em `uk_payments_terms`
com `ON CONFLICT (order_id)`. A inserção passou a usar `ON CONFLICT DO NOTHING`
para cobrir os dois índices únicos existentes, mantendo verificação de
identidade/termos e bloqueio da raiz. A suíte completa passou após a correção.
V1 não foi alterada; migrations executadas apenas em schemas temporários.
Nenhuma configuração Keycloak foi alterada na etapa 58 e as suítes dos demais
serviços não foram reexecutadas, pois seus arquivos não mudaram.
