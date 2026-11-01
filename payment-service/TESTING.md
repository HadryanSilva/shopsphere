# Validação do Payment — microtarefa 57

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
Os testes criam schemas `payment_startup_test_<UUID>` e removem exclusivamente
esses schemas no encerramento, sem alterar tabelas de outros schemas.

Para o teste opcional com Keycloak, fornecer `SHOPSPHERE_TEST_PAYMENT_SERVICE_TOKEN`
contendo um token client_credentials de `shopsphere-order`, emitido após o
provisionamento descrito no [README](README.md). Obtê-lo em memória usando
credenciais locais, sem gravar/imprimir token ou secret. O issuer padrão desse
teste é `http://localhost:8180/realms/shopsphere`, substituível por
`SHOPSPHERE_TEST_KEYCLOAK_ISSUER`. O teste usa assinatura/JWKS reais e controller
exclusivo de teste; não comprova uma operação financeira de produção.

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

Sem domínio mapeado nesta etapa: o arranque configura Hibernate `validate`,
mas a consistência JPA/colunas e as garantias transacionais do simulador ficam
para a microtarefa 58. Nenhum teste efetua cobrança real ou acessa PSP.

## Resultado verificado em 2026-11-01

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
