# Consulta de produtos em lote

`GET /api/v1/products/batch?productIds=<uuid1>&productIds=<uuid2>`

A requisição não possui corpo. O parâmetro `productIds` é obrigatório e aceita
também UUIDs separados por vírgula. A consulta é pública, assim como as demais
leituras do catálogo.

A resposta `200` é uma lista JSON direta, conforme
`catalog-products-batch-response.json`. Não existe envelope `products`.
IDs inexistentes são omitidos; se nenhum produto for encontrado, a resposta é
`[]`. A ordem dos resultados não é garantida. Consumidores devem relacionar os
produtos pelo campo `id`, sem assumir correspondência por posição.

Parâmetro ausente, lista vazia, elemento nulo ou UUID inválido resulta em `400`.

O exemplo de resposta é compartilhado pelos testes do controller do catálogo e
do cliente Feign de pedidos para verificar compatibilidade entre os serviços.
