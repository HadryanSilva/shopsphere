# Erros das APIs de domínio — microtarefa 22

Catalog, Cart e Order usam `application/problem+json` para erros resolvidos pelo
Spring MVC. O formato contém:

```json
{
  "type": "about:blank",
  "title": "Not Found",
  "status": 404,
  "detail": "Product not found",
  "instance": "/api/v1/products/20000000-0000-0000-0000-000000000001",
  "errors": []
}
```

Em Bean Validation, `errors` contém objetos com `field` e `message`, sem o
valor rejeitado. `instance` contém o caminho, sem query string. O formato das
respostas de sucesso permanece inalterado.

| Código | Uso |
|---|---|
| 400 | Body, UUID ou header inválido/ausente, Bean Validation, faixa de preços ou ordenação inválida |
| 404 | Produto, categoria, carrinho ativo ou item solicitado inexistente; pedido inexistente ou de outro cliente |
| 409 | Conflito explícito de negócio, quantidade acumulada no carrinho acima de Integer.MAX_VALUE, integridade de dados ou concorrência de persistência |
| 500 | Falha inesperada, com detalhe genérico |
| 502 | Falha HTTP de integração sem tratamento mais específico |
| 503 | Falha de conexão/leitura representada por `RetryableException` |

Outros códigos MVC, como `405` e `415`, preservam o código e headers do
framework. O tratamento estende `ResponseEntityExceptionHandler`, incluindo
validação do body e de parâmetros de método.

A remoção de item já ausente no carrinho ativo continua retornando `204`,
conforme o contrato idempotente do DELETE; o `404` de item ausente aplica-se ao PATCH.

Erros esperados são identificados explicitamente nos casos de uso, sem
comparação de mensagens de exceção. Exceções genéricas de argumento ou estado
permanecem `500`, evitando mascarar bugs internos como erros do cliente.
Erros de banco e falhas internas não devolvem SQL, mensagens do driver,
stack traces ou valores da requisição.

No Cart, o `404` do Catalog ao consultar um produto torna-se `404` na API do
carrinho. No Order, os códigos explícitos do checkout continuam preservados.
Na leitura batch do Cart, omissão de um produto também resulta em 404, sem
resposta parcial. Resposta batch nula, elementos nulos, ID/preço nulos ou IDs
duplicados são falhas de integração e retornam 502.
O replay de idempotência continua `201`: o caso de uso resolve a concorrência
antes de chegar ao tratamento HTTP.

## Compatibilidade

O corpo de erro mudou para o formato acima. Clientes devem usar `status` e os
campos documentados, sem depender de mensagens internas ou do formato padrão
anterior do Spring Boot. Recursos inexistentes antes tratados como `500`
passam a `404`; produto inativo no Cart e conflitos de persistência retornam
`409`; filtros inválidos do catálogo retornam `400`.

As regras, entry points e access-denied handlers de Spring Security foram
preservados. `401/403` dos filtros, erros próprios de roteamento do Gateway e
serviços sem endpoints de domínio não fazem parte deste contrato MVC. O
Gateway continua encaminhando os erros retornados pelos serviços de domínio.
Não há migrations ou alterações de dados nesta entrega.

## Implementação e testes

Cada domínio mantém `exception/ApiExceptionHandler.java`, sem compartilhar
código entre microsserviços. `ApiExceptionHandlerTests` verifica o mesmo
contrato nos três módulos. Os testes de aplicação exercitam controllers e
casos de uso reais com repositórios e integrações simulados. Os testes de
persistência continuam usando PostgreSQL e schemas temporários.

No diretório de cada serviço, execute:

```powershell
.\gradlew.bat test --tests '*ApiExceptionHandlerTests' --tests '*ServiceApplicationTests'
```

As suítes completas usam `./gradlew.bat test`; para PostgreSQL, configure as
variáveis descritas no `TESTING.md` do módulo.

## Inventory — microtarefa 32

Inventory adota o mesmo contrato MVC em exception/ApiExceptionHandler.java.
Também formata 401/403 dos filtros como application/problem+json, com mensagens
genéricas e errors vazio, preservando WWW-Authenticate. instance não inclui
query string. Essa extensão não altera os filtros dos três serviços anteriores.
As regras e limitações estão em [inventory-service/SECURITY.md](../inventory-service/SECURITY.md).
