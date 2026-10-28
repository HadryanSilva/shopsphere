# Testes do ShopSphere

Cada serviço possui seu próprio wrapper Gradle. Execute `./gradlew.bat test`
no diretório do módulo para rodar a suíte completa desse serviço.

## Testes sem infraestrutura externa

Os testes de inicialização usam `@ActiveProfiles("test")` e o arquivo
`src/test/resources/application-test.yaml` do respectivo módulo. Não é
necessário ativar `dev`, iniciar Docker ou disponibilizar Keycloak/PostgreSQL.

Nesse perfil, a configuração automática de datasource e o Flyway ficam
desabilitados nos serviços de domínio. Repositórios, clientes externos e o
decoder JWT usados nos testes de inicialização são substituídos por mocks.
Esses testes verificam a montagem dos componentes da aplicação; não validam
persistência nem migrações.

URLs de clientes e rotas apontam para nomes reservados `.invalid`. Os testes
de inicialização não fazem chamadas HTTP. As configurações auxiliares e os
controllers dos testes de segurança são marcados como componentes de teste
para não interferirem na aplicação iniciada pelos testes de contexto.

Os testes específicos de segurança continuam exercitando os filtros reais.
Em pedidos, o decoder real usa um servidor JWKS temporário e chaves RSA
geradas em memória. Esse teste lê o perfil `test`, sem importar configurações
de `application-dev.yaml` nem acessar o Keycloak.

## Integração com PostgreSQL

Os testes de migração são habilitados explicitamente por
`SHOPSPHERE_TEST_POSTGRES_URL`. Sem essa variável, são marcados como ignorados;
os demais testes continuam executando. Eles não usam o datasource do perfil
`test`: conectam diretamente ao PostgreSQL informado e usam schemas exclusivos.

Os detalhes estão em `catalog-service/TESTING.md` e `order-service/TESTING.md`.
O teste opt-in de inicialização Flyway/JPA do Inventory está descrito em
`inventory-service/TESTING.md`, incluindo a V1 da posição de estoque.
Para execução com banco real, configure as variáveis em uma sessão PowerShell:

```powershell
$env:SHOPSPHERE_TEST_POSTGRES_URL = 'jdbc:postgresql://localhost:5432/order_db'
$env:SHOPSPHERE_TEST_POSTGRES_USER = 'postgres'
$env:SHOPSPHERE_TEST_POSTGRES_PASSWORD = 'postgres'
.\gradlew.bat test --rerun-tasks
```

Execute o comando dentro do serviço desejado. O usuário do banco precisa de
permissão para criar e remover schemas. Para voltar à execução sem PostgreSQL:

```powershell
Remove-Item Env:SHOPSPHERE_TEST_POSTGRES_URL -ErrorAction SilentlyContinue
```

Não use esse perfil para executar a aplicação fora dos testes. Ele não testa
conectividade com dependências externas nem substitui a integração com PostgreSQL.

## Fluxo Cart–Order–Catalog pelo Gateway

A suíte opt-in CheckoutFlowIntegrationTests inicia os quatro JARs reais,
usa PostgreSQL em schemas exclusivos e valida HTTP, segurança JWT e
recuperação com reinícios/falhas de rede. Pré-requisitos, comandos e limites
em [order-service/CHECKOUT_OPERATION.md](order-service/CHECKOUT_OPERATION.md).
Ela usa emissor/JWKS local de teste e não exige nem modifica Keycloak.
