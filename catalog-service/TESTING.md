# Validação do esquema do catálogo

A migração V3 converte `products.version` de `NUMERIC` para `BIGINT`, compatível
com o `long` usado pelo Hibernate. V1 e V2 permanecem intactas para preservar os
checksums de bancos que já executaram essas migrações. O mapeamento de
`imageUrl` usa o limite de 2048 caracteres definido no SQL.

O teste `CatalogSchemaMigrationTests` exige PostgreSQL e um usuário com
permissão para criar e remover schemas. Ele cria um schema exclusivo, executa
V1/V2, insere um produto, aplica V3 e valida o esquema com o Hibernate. Também
verifica preservação dos dados, incremento da versão e persistência de URL com
mais de 500 caracteres. O schema gerado é removido ao final.

Execute no diretório `catalog-service`, em PowerShell:

```powershell
$env:SHOPSPHERE_TEST_POSTGRES_URL = 'jdbc:postgresql://localhost:5432/catalog_db'
$env:SHOPSPHERE_TEST_POSTGRES_USER = 'postgres'
$env:SHOPSPHERE_TEST_POSTGRES_PASSWORD = 'postgres'
.\gradlew.bat test --tests '*CatalogSchemaMigrationTests' --rerun-tasks
```

Sem `SHOPSPHERE_TEST_POSTGRES_URL`, o teste é marcado como ignorado.
`--rerun-tasks` força nova execução caso a tarefa anterior tenha sido ignorada.
O teste usa conexão direta com o banco e não depende do Keycloak.

## Validação de entrada — microtarefa 21

Na criação de produtos, preço é obrigatório, positivo e limitado a 17 dígitos
inteiros e duas casas decimais, conforme `NUMERIC(19,2)`. Nome é obrigatório,
não pode conter apenas espaços e tem até 150 caracteres. Descrição e imagem
continuam opcionais, com limites de 2000 e 2048 caracteres. Categoria e moeda
continuam obrigatórias.

No PATCH de produto, todos os campos continuam opcionais. Quando informados,
nome não pode ser vazio ou composto apenas de espaços, textos respeitam os
mesmos limites da criação e preço deve ser positivo e caber em `NUMERIC(19,2)`.
Campos nulos continuam sendo ignorados e `{}` mantém o comportamento anterior.
O controller agora executa Bean Validation também no PATCH.

Na criação de categorias, nome e descrição mantêm a obrigatoriedade já
existente e passam a respeitar os limites de 100 e 500 caracteres do banco.

A busca em lote exige ao menos um UUID e rejeita elementos nulos, inclusive
quando um parâmetro vazio aparece junto com IDs válidos. Os formatos de UUIDs
repetidos ou separados por vírgula continuam aceitos. Uma lista válida sem
produtos encontrados continua retornando `200` com `[]`.

`CatalogRequestValidationTests` verifica retornos `400` antes dos casos de uso,
valores nos limites, campos opcionais e PATCH parcial. `ProductBatchContractTests`
cobre o contrato e as entradas inválidas da busca em lote. Execute:

```powershell
.\gradlew.bat test --tests '*CatalogRequestValidationTests' --tests '*ProductBatchContractTests'
```

Não há alteração de schema, nomes de campos, rotas ou permissões nesta tarefa.
As validações de quantidade do carrinho e de endereço do checkout já estão
ativas nas respectivas APIs. Itens enviados pelo frontend no checkout continuam
ignorados, conforme o contrato existente; a fonte das quantidades é o Cart.

## Erros padronizados — microtarefa 22

O contrato está em `../contracts/api-errors.md`. Produto/categoria inexistente
retorna `404`; faixa de preços e ordenação inválida retornam `400`; conflitos
de integridade retornam `409`. `ApiExceptionHandlerTests` valida o formato e
`CatalogServiceApplicationTests` exercita os casos de uso reais com repositórios
simulados. Headers padrão, como `Allow` no `405`, são preservados.

## Moeda dos produtos — microtarefa 23

A criação de produtos aceita apenas `currency: "EUR"`, mantendo o campo
obrigatório. USD, GBP, CHF e outros valores retornam `400` em
`application/problem+json`, antes do caso de uso e sem gravar o produto.
Isso impede que um valor enviado em outra moeda seja silenciosamente tratado
como EUR. Consumidores que enviavam outras moedas precisam enviar preços em
EUR; não há conversão cambial automática.

`CatalogRequestValidationTests` cobre a rejeição de USD/GBP/CHF, os valores
inválidos/ausentes e a resposta EUR na criação válida. Execute:

```powershell
.\gradlew.bat test --tests '*CatalogRequestValidationTests' --offline --no-daemon
```

Não há alteração de schema, migrations ou dados existentes. Valores não EUR
inseridos diretamente no banco não são convertidos nem corrigidos nesta tarefa.

## Location da categoria criada — microtarefa 28

`POST /api/v1/categories` mantém o retorno `201` e o body `CategoryResponse`.
O header Location agora aponta para `/api/v1/categories/{id}`, com o ID da
categoria devolvida pelo caso de uso, em vez de apontar para a coleção.
Consumidores podem seguir esse endereço com o GET já existente.

`CatalogServiceApplicationTests.createdCategoryLocationPointsToReturnedResource`
exercita o controller e os casos de uso reais com repositório simulado:
verifica status/header/body do POST e faz GET no Location, conferindo que a
resposta identifica a mesma categoria. Execute no diretório `catalog-service`:

```powershell
.\gradlew.bat test --tests '*CatalogServiceApplicationTests' --tests '*CatalogRequestValidationTests' --offline --no-daemon
```

Não há novas rotas, DTOs, migrations, dependências ou mudanças de permissões.

Validação desta entrega: testes focados aprovados; com
`SHOPSPHERE_TEST_POSTGRES_URL` apontando para `catalog_db` local,
`.\gradlew.bat test --offline --no-daemon --rerun-tasks` aprovou a suíte
completa de 85 testes, sem falhas ou ignorados, incluindo a integração Flyway/JPA.
