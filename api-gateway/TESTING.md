# Roteamento de categorias — microtarefa 24

O Gateway encaminha `/api/v1/categories/**` para a mesma URL configurável do
Catalog usada por produtos (`services.catalog.url`). O caminho não é removido
nem reescrito. As permissões existentes permanecem: GET público e POST com
role ADMIN. O Catalog continua responsável por validar o token e as permissões
no serviço de domínio.

`GatewayCategoryRoutingTests` inicia o Gateway numa porta aleatória e um
servidor HTTP local que simula o Catalog. Verifica encaminhamento GET com
caminho/query preservados, POST com body e Authorization preservados e retorno
do status, corpo e Location fornecidos pelo destino. Verifica também que POST
anónimo retorna 401 e POST de CUSTOMER retorna 403 sem alcançar o Catalog.

O decoder JWT é simulado; o teste exercita os filtros reais e o encaminhamento
HTTP, mas não valida assinatura de tokens nem comunicação com Keycloak ou com
uma instância real do Catalog. O Location simulado corresponde ao header do
Catalog corrigido na microtarefa 28; o teste verifica o seu encaminhamento.

Execute no diretório `api-gateway`:

```powershell
.\gradlew.bat test --tests '*GatewayCategoryRoutingTests' --tests '*GatewaySecurityTests' --offline --no-daemon
.\gradlew.bat test --offline --no-daemon
```

Os testes não exigem PostgreSQL, Docker ou Keycloak. Não há migrations,
dependências novas ou alterações nas regras de segurança nesta tarefa.

## Inventory administrativo — microtarefa 40

Rotas, permissões, URL/timeouts e arquivos estão em
[INVENTORY.md](INVENTORY.md). Executados no diretório api-gateway:

```powershell
.\gradlew.bat test --tests '*GatewayInventory*Tests' --offline --no-daemon
.\gradlew.bat test --offline --no-daemon --rerun-tasks
```

Resultado final: 78 testes aprovados, zero falhas/erros e nenhum ignorado.
Inclui 33 testes HTTP de Inventory, 16 de predicados de rota e cinco de
configuração do cliente, além das regressões de categorias/segurança/arranque.

A validação do cliente dedicado identificou inicialmente a necessidade de
receber ContextRefreshedEvent para inicializar os filtros de headers MVC.
O handler foi corrigido para delegar esse evento ao proxy; a repetição dos
testes focados e a suíte completa final passaram.

HTTP real até servidor local que simula Inventory, com decoder JWT simulado
e filtros reais. Verificados os cinco caminhos aprovados, path/query/body,
Unicode, Authorization, Idempotency-Key, Location, erros HTTP do destino,
401/403 antes do encaminhamento e rotas internas/operações não aprovadas.
O teste de timeout usa leitura de 500 ms e resposta atrasada em 1500 ms;
verifica 503 genérico e uma única tentativa do POST. Predicados são testados
também sem o filtro de segurança.

Não foi executada uma cadeia com Inventory e Keycloak reais nesta entrega.
Não há necessidade de PostgreSQL/Docker/Keycloak para estes testes, novas
migrations ou mudanças em dependências. Código de domínio do Inventory não
foi alterado; sua autenticação independente permanece implementada.
